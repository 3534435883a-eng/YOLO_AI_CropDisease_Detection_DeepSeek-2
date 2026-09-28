package com.example.Ece.agent.plan;

import com.alibaba.fastjson.JSON;
import com.example.Ece.config.AgriPlanProperties;
import com.example.Ece.dto.ai.AiChatResponse;
import com.example.Ece.dto.ai.ChatMessage;
import com.example.Ece.service.DeepSeekException;
import com.example.Ece.service.DeepSeekService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 农事规划推演：让大模型用自身知识做条件推演，产出方案。
 *
 * <p><b>本类刻意不复用 {@code AgentOrchestrator}。</b>编排器带三道闸门——无可靠证据即拒答、
 * 引用编号强制校验、越权与药剂护栏——它们与"放开模型用自身知识推演"逐条冲突。
 * 用 {@code mode} 开关塞进同一个编排器，会让两条回答政策的护栏逻辑互相污染，
 * 而严格通道那边刚调好（难负样本拒答率 16.7% → 94.4%），动它是净损失。</p>
 *
 * <p>代价要如实说：不检索、不强制引用，意味着这里**没有可核对的出处**。
 * 因此推演的边界只能靠两件事守——出口处强制补推演声明，以及提示词里的硬性约束
 * （不得声称已执行、数据缺失要明说、药剂口径）。这几条都写在 {@link DeductionPrompts} 里，
 * 改动那里等于改动推演通道对用户的承诺。</p>
 */
@Service
public class PlanDeductionService {

    private static final Logger log = LoggerFactory.getLogger(PlanDeductionService.class);

    /** 默认种子。固定值让"同一份农情跑两次"可对比；要变化由调用方显式传。 */
    public static final long DEFAULT_SEED = 20260928L;

    private final DeepSeekService deepSeekService;
    private final AgriPlanBaseline baselineCalculator;
    private final AgriPlanProperties properties;

    public PlanDeductionService(DeepSeekService deepSeekService,
                                AgriPlanBaseline baselineCalculator,
                                AgriPlanProperties properties) {
        this.deepSeekService = deepSeekService;
        this.baselineCalculator = baselineCalculator;
        this.properties = properties;
    }

    /**
     * 执行一次推演。
     *
     * @param situation 结构化农情；可为空对象，此时提示词会说明"用户未提供数据"
     * @param question  用户的自然语言诉求；可为 null
     * @param seed      基线种子；null 用默认值
     * @param days      基线天数；null 或非正数用配置默认值
     * @param sink      事件接收方；可为 null（仅取返回值时）
     * @param cancelled 取消信号。返回 true 时**中止上游读取**并抛 {@link DeductionCancelledException}——
     *                  不能只是"不再下发"，那样上游会继续生成、继续计费
     * @throws DeductionCancelledException 调用方要求取消时
     */
    public DeductionResult deduce(AgriSituationInput situation, String question, Long seed, Integer days,
                                  Consumer<DeductionEvent> sink, BooleanSupplier cancelled) {
        long startedAt = System.currentTimeMillis();
        AgriSituationInput actualSituation = situation == null ? new AgriSituationInput() : situation;
        long effectiveSeed = seed == null ? DEFAULT_SEED : seed.longValue();
        int effectiveDays = days == null || days.intValue() <= 0
                ? properties.getDefaultDays() : days.intValue();

        emit(sink, DeductionEvent.stage("baseline", "正在计算机理模型参考基线…"));
        AgriPlanBaseline.Baseline baseline = baselineCalculator.compute(effectiveSeed, effectiveDays);
        emit(sink, new DeductionEvent(DeductionEvent.TYPE_BASELINE, baselinePayload(baseline)));

        emit(sink, DeductionEvent.stage("deduce", "模型正在推演…"));
        List<ChatMessage> messages = buildMessages(actualSituation, question, baseline);

        StreamAttempt attempt = runModel(messages, sink, cancelled);
        // 降级路径是整段返回、没有中途取消的机会，所以这里再兜一次：
        // 用户在"整段返回"期间关掉页面，同样不该把结果落库。
        if (cancelled.getAsBoolean()) {
            throw new DeductionCancelledException();
        }

        DeductionOutput output = DeductionOutput.parse(attempt.raw);
        DeductionResult result = new DeductionResult(
                output.getMarkdown(),
                output.isBannerInjected(),
                output.isStructuredParsed() ? output.getStructured().toJSONString() : null,
                output.getSections(),
                actualSituation.missingLabels(),
                baseline,
                attempt.streamMode,
                attempt.fallbackReason,
                System.currentTimeMillis() - startedAt);

        emit(sink, new DeductionEvent(DeductionEvent.TYPE_FINAL, finalPayload(result)));
        return result;
    }

    /**
     * 调模型，处理三条路径：正常流式、截断重试、流式不可用降级。
     *
     * <p>三条路径的**降级都如实标注**（{@code streamMode} + {@code fallbackReason}）。
     * 把降级结果当正常结果下发，用户就无从判断"为什么这次没有逐字出现"。</p>
     */
    private StreamAttempt runModel(List<ChatMessage> messages, Consumer<DeductionEvent> sink,
                                   BooleanSupplier cancelled) {
        DeltaBatcher batcher = new DeltaBatcher(sink, properties.getDeltaFlushMillis());
        try {
            String raw = deepSeekService.chatStream(messages, DeepSeekService.ChatOptions.simulating(),
                    (text, reasoning) -> {
                        // 返回 false 会让 chatStream 立即关闭上游连接——这是"取消"唯一的实效，
                        // 只停下发是不够的：上游会继续生成、继续计费。
                        if (cancelled.getAsBoolean()) {
                            return false;
                        }
                        batcher.accept(text, reasoning);
                        return true;
                    });
            // 取消时 chatStream 返回的是**半截正文**，它看起来与完整回答无异。
            // 必须在这里短路，否则半截方案会被当成完整方案下发并落库。
            if (cancelled.getAsBoolean()) {
                throw new DeductionCancelledException();
            }
            batcher.flush();
            return new StreamAttempt(raw, DeductionResult.StreamMode.STREAM, null);
        } catch (DeepSeekException error) {
            batcher.flush();
            String code = error.getCode();
            if (DeepSeekException.CODE_OUTPUT_TRUNCATED.equals(code)) {
                // 截断：关掉思考重来一次。先发 reset，否则前端会把半截正文与新正文拼在一起。
                emit(sink, new DeductionEvent(DeductionEvent.TYPE_RESET, null));
                return retryWithoutThinking(messages, sink, "输出被截断，已关闭思考模式重试");
            }
            if (DeepSeekException.CODE_STREAM_UNAVAILABLE.equals(code)
                    || DeepSeekException.CODE_NETWORK_ERROR.equals(code)) {
                // 流式通道不可用：退回整段返回。用户拿得到结果，但要如实说明这不是流式。
                return fallbackNonStreaming(messages, sink,
                        DeepSeekException.CODE_STREAM_UNAVAILABLE.equals(code)
                                ? "流式通道未就绪，已退回整段返回" : "流式连接失败，已退回整段返回");
            }
            throw error;
        }
    }

    private StreamAttempt retryWithoutThinking(List<ChatMessage> messages, Consumer<DeductionEvent> sink,
                                               String reason) {
        List<ChatMessage> retryMessages = new ArrayList<ChatMessage>(messages);
        retryMessages.add(message("user", DeductionPrompts.RETRY_HINT));
        AiChatResponse response = deepSeekService.chatWithStreamingTimeout(retryMessages,
                DeepSeekService.ChatOptions.composingWithoutThinking());
        String text = response == null ? null : response.getContent();
        emit(sink, new DeductionEvent(DeductionEvent.TYPE_DELTA, deltaEvent(text == null ? "" : text, false)));
        return new StreamAttempt(text, DeductionResult.StreamMode.FALLBACK, reason);
    }

    private StreamAttempt fallbackNonStreaming(List<ChatMessage> messages, Consumer<DeductionEvent> sink,
                                               String reason) {
        AiChatResponse response = deepSeekService.chatWithStreamingTimeout(messages,
                DeepSeekService.ChatOptions.simulating());
        String text = response == null ? null : response.getContent();
        emit(sink, new DeductionEvent(DeductionEvent.TYPE_DELTA, deltaEvent(text == null ? "" : text, false)));
        return new StreamAttempt(text, DeductionResult.StreamMode.FALLBACK, reason);
    }

    private List<ChatMessage> buildMessages(AgriSituationInput situation, String question,
                                            AgriPlanBaseline.Baseline baseline) {
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        messages.add(message("system", DeductionPrompts.systemPrompt(properties.isAllowPesticideDosage())));
        messages.add(message("user", DeductionPrompts.userPrompt(situation,
                baseline == null ? null : baseline.getPromptBlock(), question)));
        return messages;
    }

    private ChatMessage message(String role, String content) {
        ChatMessage message = new ChatMessage();
        message.setRole(role);
        message.setContent(content);
        return message;
    }

    private Map<String, Object> baselinePayload(AgriPlanBaseline.Baseline baseline) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("available", Boolean.valueOf(baseline.isAvailable()));
        payload.put("unavailableReason", baseline.getUnavailableReason());
        payload.put("batchId", baseline.getBatchId());
        payload.put("seed", Long.valueOf(baseline.getSeed()));
        payload.put("days", Integer.valueOf(baseline.getDays()));
        payload.put("strategyLabel", baseline.getStrategyLabel());
        payload.put("strategyCode", baseline.getStrategyCode());
        payload.put("marketableYieldKg", Double.valueOf(baseline.getMarketableYieldKg()));
        payload.put("yieldPerSquareMeter", Double.valueOf(baseline.getYieldPerSquareMeter()));
        payload.put("profitYuan", Double.valueOf(baseline.getProfitYuan()));
        payload.put("waterUsedM3", Double.valueOf(baseline.getWaterUsedM3()));
        payload.put("energyKwh", Double.valueOf(baseline.getEnergyKwh()));
        payload.put("highTemperatureMinutes", Long.valueOf(baseline.getHighTemperatureMinutes()));
        // 这句话必须随数值一起下发，且要能被界面显著展示：
        // 它是这份数值唯一没说谎的前提。
        payload.put("scopeNote", "固定标准情景基线，不采用你提供的农情输入，不是对你棚况的预测");
        // 窗口不足的解释（利润为负时非 null）。界面旁边就是那个负利润数字，
        // 不解释的话，看到的人只会得出一个反向结论。
        payload.put("windowNote", baseline.getWindowNote());
        return payload;
    }

    private Map<String, Object> finalPayload(DeductionResult result) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("markdown", result.getMarkdown());
        // 结构化动作清单单独下发：界面把它渲染成可勾选的动作/日程/风险，
        // 而不是让用户从一大段散文里自己找该干什么——这是"可操作性"的落点。
        // 模型没给出可解析结果时为 null，界面据此显示"未返回结构化清单"而不是空白列表。
        payload.put("structured", result.getStructuredJson() == null
                ? null : JSON.parseObject(result.getStructuredJson()));
        payload.put("bannerInjected", Boolean.valueOf(result.isBannerInjected()));
        payload.put("sections", result.getSections());
        payload.put("missingFields", result.getMissingFields());
        payload.put("streamMode", result.getStreamMode().name());
        payload.put("fallbackReason", result.getFallbackReason());
        payload.put("elapsedMillis", Long.valueOf(result.getElapsedMillis()));
        payload.put("baseline", baselinePayload(result.getBaseline()));
        return payload;
    }

    private Map<String, Object> deltaEvent(String text, boolean reasoning) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("text", text);
        payload.put("reasoning", Boolean.valueOf(reasoning));
        return payload;
    }

    private void emit(Consumer<DeductionEvent> sink, DeductionEvent event) {
        if (sink == null) {
            return;
        }
        try {
            sink.accept(event);
        } catch (RuntimeException error) {
            // 下发失败（客户端断开）不该中断推演本身——推演的终态仍要落库供审计。
            log.debug("推演事件下发失败，已忽略：{}", error.toString());
        }
    }

    /** 一次模型调用的结果。 */
    private static final class StreamAttempt {
        private final String raw;
        private final DeductionResult.StreamMode streamMode;
        private final String fallbackReason;

        private StreamAttempt(String raw, DeductionResult.StreamMode streamMode, String fallbackReason) {
            this.raw = raw;
            this.streamMode = streamMode;
            this.fallbackReason = fallbackReason;
        }
    }

    /**
     * 增量合并器：把细碎的 token 攒一小批再下发。
     *
     * <p>思考与正文**分别攒**，否则两类增量会混进同一个批次，前端分栏就错位了。
     * 只按时间判断是否该发（不额外起线程）：delta 回调本来就在推演线程上，
     * 攒批逻辑放在同一个线程里最简单也最不容易出错。</p>
     */
    private static final class DeltaBatcher {

        private final Consumer<DeductionEvent> sink;
        private final long flushMillis;
        private final StringBuilder content = new StringBuilder();
        private final StringBuilder reasoning = new StringBuilder();
        private long lastFlushAt = System.currentTimeMillis();

        private DeltaBatcher(Consumer<DeductionEvent> sink, long flushMillis) {
            this.sink = sink;
            this.flushMillis = flushMillis <= 0 ? 60L : flushMillis;
        }

        private void accept(String text, boolean isReasoning) {
            if (text == null || text.isEmpty()) {
                return;
            }
            (isReasoning ? reasoning : content).append(text);
            long now = System.currentTimeMillis();
            if (now - lastFlushAt >= flushMillis) {
                flush();
                lastFlushAt = now;
            }
        }

        private void flush() {
            if (sink == null) {
                content.setLength(0);
                reasoning.setLength(0);
                return;
            }
            if (reasoning.length() > 0) {
                sink.accept(deltaEventOf(reasoning.toString(), true));
                reasoning.setLength(0);
            }
            if (content.length() > 0) {
                sink.accept(deltaEventOf(content.toString(), false));
                content.setLength(0);
            }
        }

        private DeductionEvent deltaEventOf(String text, boolean reasoning) {
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("text", text);
            payload.put("reasoning", Boolean.valueOf(reasoning));
            return new DeductionEvent(DeductionEvent.TYPE_DELTA, payload);
        }
    }
}
