package com.example.Ece.agent.orchestrator;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.example.Ece.agent.guard.GuardrailCheck;
import com.example.Ece.agent.guard.GuardrailService;
import com.example.Ece.agent.guard.CitationReferenceValidator;
import com.example.Ece.agent.rag.CitationFormatter;
import com.example.Ece.agent.rag.KnowledgeChunker;
import com.example.Ece.agent.rag.ScoredChunk;
import com.example.Ece.agent.tool.AgentTool;
import com.example.Ece.agent.tool.AgentToolRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 智能体编排循环：理解意图 → 选择工具 → 执行 → 观察 → 再决策。
 *
 * 三道闸门保证可控：
 * 1) 规划步数上限 {@link #MAX_STEPS}（含"工具不存在"这类无效规划，防止空转）；
 * 2) 单工具调用次数与"同参数重复调用"拦截；
 * 3) 单步与整轮超时（单步经线程池 {@code Future.get} 强制超时）。
 *
 * 证据可靠性由检索的 lowScore 判定：只有存在关键词证据的命中才算可靠，
 * 否则拒答而不是拿语义漂移的结果编答案。引用编号在每轮合并后全局重编号，
 * 保证 LLM 看到的编号与最终展示完全一致。
 */
@Component
public class AgentOrchestrator {

    public static final int MAX_STEPS = 6;
    public static final int MAX_TOOL_REPEAT = 2;
    public static final long STEP_TIMEOUT_MS = 20000L;
    public static final long TOTAL_TIMEOUT_MS = 90000L;
    /**
     * 无可靠依据时的统一答复。
     *
     * <p>措辞要求：**直接说"资料库不足"**，不要只说"没有检索到可靠依据"——后者让用户不知道
     * 是资料缺口还是检索没命中。具体原因由工具通过 {@code note} 追加在括号里
     *（关键词零命中 / 检索到但相关性不足）。</p>
     */
    public static final String REFUSAL_ANSWER =
            "资料库不足：知识库里没有能支撑这个问题的可靠依据，暂不给出结论；"
                    + "建议补充作物、发病部位或症状描述，或联系当地农技人员核实。";

    /**
     * 作答阶段的系统提示。
     *
     * <p><b>为什么必须单独一套</b>：规划阶段的系统提示写着"每一步只输出一个 JSON"，
     * 模型会一直遵守——实测真实端到端时，作答步返回的是
     * {@code {"tool":"FINALIZE","input":{"answer":"**结论**：…"}}}，用户看到的就是一坨 JSON。
     * 作答阶段必须显式解除 JSON 约束（并继续禁止越权执行声明）。</p>
     */
    static final String ANSWER_SYSTEM_PROMPT =
            "你是面向番茄设施种植的农业智能体，当前进入【作答】阶段。请直接用中文散文输出最终答复："
                    + "先给结论，再给依据与[编号]引用（编号必须与上文证据完全一致），最后给风险与注意事项。"
                    + "不要输出 JSON，不要复述工具名或调用过程。证据不足时明确说明依据不足，不得臆造。"
                    + "不得声称已经自动执行了任何设备操作。"
                    // 实测漏判："如何给番茄施肥"只能检索到病害类片段，模型会拿相近主题硬答。
                    // 知识库当前只覆盖病害，遇到栽培/水肥/环境类问题必须如实说明缺依据。
                    + "若检索到的证据与问题主题不一致（例如问施肥/灌溉/温度管理却只拿到病害片段），"
                    + "必须明确说明知识库当前缺少该主题的依据，不得用相近主题的证据作答。";

    private final AgentToolRegistry registry;
    private final LlmClient llmClient;
    private final CitationFormatter citationFormatter = new CitationFormatter();
    private final GuardrailService guardrailService;
    private final CitationReferenceValidator citationReferenceValidator = new CitationReferenceValidator();
    private final SessionHistoryStore sessionHistoryStore = new SessionHistoryStore();
    private final ExecutorService toolExecutor = Executors.newCachedThreadPool(new ThreadFactory() {
        private final AtomicInteger counter = new AtomicInteger();

        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "agent-tool-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    });

    @Autowired
    public AgentOrchestrator(AgentToolRegistry registry, LlmClient llmClient, GuardrailService guardrailService) {
        this.registry = registry;
        this.llmClient = llmClient;
        this.guardrailService = guardrailService;
    }

    /** 便于单元测试：使用默认守门实现。 */
    public AgentOrchestrator(AgentToolRegistry registry, LlmClient llmClient) {
        this(registry, llmClient, new GuardrailService());
    }

    @PreDestroy
    public void shutdown() {
        toolExecutor.shutdownNow();
    }

    @SuppressWarnings("unchecked")
    public AgentResult run(String sessionId, String question, String crop, Consumer<AgentStepEvent> sink) {
        List<AgentStepEvent> events = new ArrayList<AgentStepEvent>();
        List<Map<String, Object>> priorHistory = sessionHistoryStore.snapshot(sessionId);
        List<Map<String, Object>> history = new ArrayList<Map<String, Object>>();
        history.add(message("system", systemPrompt()));
        // 历史消息放在 system 提示之后，避免旧会话内容覆盖当前编排约束。
        history.addAll(priorHistory);
        history.add(message("user", userPrompt(question, crop)));

        Map<String, Integer> toolCalls = new HashMap<String, Integer>();
        Set<String> seenDigests = new HashSet<String>();
        List<Map<String, Object>> citations = new ArrayList<Map<String, Object>>();
        List<ScoredChunk> evidenceChunks = new ArrayList<ScoredChunk>();
        boolean reliableEvidence = false;
        boolean degradedSeen = false;
        // 工具给出的"解释性说明"（如视觉类别对应哪条知识、为何没有依据）。
        // 有证据时喂给作答步以便回答点明依据来源；无证据时如实写进拒答文案，
        // 避免用户只看到一句"没有可靠依据"而不知道到底为什么。
        List<String> observations = new ArrayList<String>();
        String blockReason = null;
        long startedAt = System.currentTimeMillis();
        int planSteps = 0;
        int executed = 0;

        while (planSteps < MAX_STEPS) {
            planSteps++;
            if (System.currentTimeMillis() - startedAt > TOTAL_TIMEOUT_MS) {
                blockReason = "TOTAL_TIMEOUT";
                break;
            }
            String rawPlan;
            try {
                rawPlan = llmClient.plan(history);
            } catch (RuntimeException error) {
                return finish(events, sink, new ArrayList<Map<String, Object>>(), executed, null,
                        AgentResult.Status.ERROR, "LLM_ERROR", error.getClass().getSimpleName());
            }
            JSONObject plan = parsePlan(rawPlan);
            if (plan == null) {
                blockReason = "PLAN_UNPARSEABLE";
                break;
            }
            String toolName = plan.getString("tool");
            if (toolName == null || "FINALIZE".equalsIgnoreCase(toolName)) {
                break;
            }
            AgentTool tool = registry.find(toolName);
            if (tool == null) {
                history.add(message("user", "工具 " + toolName + " 不存在，请从目录中选择，或直接 FINALIZE。"));
                continue;
            }
            Map<String, Object> input = toMap(plan.getJSONObject("input"));
            String digest = KnowledgeChunker.sha256(toolName + "|" + input);
            if (seenDigests.contains(digest)) {
                blockReason = "DUPLICATE_TOOL_CALL";
                break;
            }
            Integer used = toolCalls.get(toolName);
            if (used != null && used.intValue() >= MAX_TOOL_REPEAT) {
                blockReason = "TOOL_REPEAT_LIMIT";
                break;
            }
            seenDigests.add(digest);
            toolCalls.put(toolName, Integer.valueOf(used == null ? 1 : used.intValue() + 1));

            long stepStartedAt = System.currentTimeMillis();
            Map<String, Object> output = executeWithTimeout(tool, input);
            long durationMs = System.currentTimeMillis() - stepStartedAt;
            executed++;

            List<Map<String, Object>> stepCitations = new ArrayList<Map<String, Object>>();
            Object rawCitations = output.get("citations");
            boolean lowScore = Boolean.TRUE.equals(output.get("lowScore"));
            if (rawCitations instanceof List) {
                stepCitations.addAll((List<Map<String, Object>>) rawCitations);
            }
            if (Boolean.TRUE.equals(output.get("degraded"))) {
                degradedSeen = true;
            }
            Object rawItems = output.get("items");
            if (rawItems instanceof List && !lowScore && output.get("error") == null) {
                evidenceChunks.addAll((List<ScoredChunk>) rawItems);
            }
            if (!stepCitations.isEmpty() && !lowScore && output.get("error") == null) {
                citations = citationFormatter.merge(citations, stepCitations);
                reliableEvidence = true;
                history.add(message("user", "已获得证据（编号全局一致，回答时必须使用这套编号）：\n"
                        + citationFormatter.toEvidenceBlock(citations)));
            } else {
                history.add(message("user", "工具 " + toolName + " 未提供可用证据"
                        + (lowScore ? "（相关性不足）" : (output.get("error") == null ? "（无命中）" : "（执行失败）")) + "。"));
            }
            Object rawNote = output.get("note");
            if (rawNote != null && !String.valueOf(rawNote).trim().isEmpty()) {
                String note = String.valueOf(rawNote).trim();
                observations.add(note);
                history.add(message("user", "工具 " + toolName + " 说明：" + note));
            }

            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("citations", citations);
            payload.put("stepCitations", stepCitations);
            payload.put("degraded", Boolean.valueOf(Boolean.TRUE.equals(output.get("degraded"))));
            payload.put("degradedReason", output.get("degradedReason"));
            payload.put("lowScore", Boolean.valueOf(lowScore));
            payload.put("durationMs", Long.valueOf(durationMs));
            payload.put("error", output.get("error"));
            // 工具入参一并下发：前端可展示"用什么参数调的"，排查时也不必靠猜。
            payload.put("input", input);
            payload.put("note", output.get("note"));
            payload.put("mapping", output.get("mapping"));

            AgentStepEvent stepEvent = new AgentStepEvent("step", executed, toolName,
                    summarize(toolName, stepCitations.size(), durationMs, output), payload);
            events.add(stepEvent);
            if (sink != null) {
                sink.accept(stepEvent);
            }

            // 工具可给出**终止信号**：例如"该检测类别在知识库里没有可核对条目"、"同名类别需先确认作物"。
            // 这类问题再检索也答不出来，继续循环只会让模型拿相近主题的证据硬答
            //（实测问"潜叶虫"时检索到 7 条番茄病害并试图作答）。因此立即结束本轮，
            // 把工具给出的答复文案原样交给用户——该说"资料库不足"就直接说。
            Object terminalAnswer = output.get("terminalAnswer");
            if (Boolean.TRUE.equals(output.get("terminal")) && terminalAnswer != null) {
                Object terminalReason = output.get("terminalReason");
                return finish(events, sink, new ArrayList<Map<String, Object>>(), executed, null,
                        AgentResult.Status.REFUSED,
                        terminalReason == null ? "TOOL_TERMINAL" : String.valueOf(terminalReason),
                        String.valueOf(terminalAnswer));
            }
        }

        // 没有可靠证据时直接拒答，**不再调用作答步**：既省一次大模型往返，
        // 也不给模型"顺手编个结论"的机会——这条路径的答案本来就会被丢弃。
        if (!reliableEvidence) {
            String refusal = observations.isEmpty() ? REFUSAL_ANSWER
                    : REFUSAL_ANSWER + "（" + String.join("；", observations) + "）";
            return finish(events, sink, new ArrayList<Map<String, Object>>(), executed, null,
                    AgentResult.Status.REFUSED, blockReason == null ? "NO_RELIABLE_EVIDENCE" : blockReason,
                    refusal);
        }

        String answer = null;
        try {
            answer = unwrapAnswer(llmClient.compose(composeHistory(history)));
        } catch (RuntimeException error) {
            answer = null;
        }
        if (answer == null || answer.trim().isEmpty()) {
            // 有证据却拿不到回答（模型调用失败或输出被截断）：如实按未完成返回。
            // 曾用拒答文案兜底并报 DONE，界面上会显示成"结论"，把失败伪装成成功。
            return finish(events, sink, new ArrayList<Map<String, Object>>(), executed, null,
                    AgentResult.Status.REFUSED, "ANSWER_EMPTY", REFUSAL_ANSWER);
        }
        GuardrailCheck guardrail = guardrailService.check(answer, evidenceChunks, degradedSeen);

        if (guardrail.isAllowed() && !citationReferenceValidator.hasValidReferences(
                guardrail.getRewrittenAnswer(), citations.size())) {
            return finish(events, sink, new ArrayList<Map<String, Object>>(), executed, null,
                    AgentResult.Status.REFUSED, GuardrailService.REASON_NO_EVIDENCE,
                    REFUSAL_ANSWER + "（回答未包含可核对的有效引用编号）");
        }

        // 被安全守门判为"越权执行声明"时给一次重写机会：实测多数情况是模型顺手写了"已自动…"，
        // 并非真要越权。重写一次既不放行声明本身，也避免把整段有依据的分析直接丢成拒答。
        if (!guardrail.isAllowed() && GuardrailService.REASON_AUTO_EXECUTION_CLAIM.equals(guardrail.getReason())) {
            String retry = null;
            try {
                retry = unwrapAnswer(llmClient.compose(composeHistoryAfterExecutionClaim(history)));
            } catch (RuntimeException error) {
                retry = null;
            }
            if (retry != null && !retry.trim().isEmpty()) {
                GuardrailCheck recheck = guardrailService.check(retry, evidenceChunks, degradedSeen);
                if (recheck.isAllowed()) {
                    if (!citationReferenceValidator.hasValidReferences(recheck.getRewrittenAnswer(), citations.size())) {
                        return finish(events, sink, new ArrayList<Map<String, Object>>(), executed, null,
                                AgentResult.Status.REFUSED, GuardrailService.REASON_NO_EVIDENCE,
                                REFUSAL_ANSWER + "（回答未包含可核对的有效引用编号）");
                    }
                    sessionHistoryStore.append(sessionId, question, recheck.getRewrittenAnswer());
                    return finish(events, sink, citations, executed, recheck.getRewrittenAnswer(),
                            AgentResult.Status.DONE, blockReason, null);
                }
                guardrail = recheck;
            }
        }
        if (!guardrail.isAllowed()) {
            return finish(events, sink, new ArrayList<Map<String, Object>>(), executed, null,
                    AgentResult.Status.REFUSED, guardrail.getReason(), REFUSAL_ANSWER);
        }
        sessionHistoryStore.append(sessionId, question, guardrail.getRewrittenAnswer());
        return finish(events, sink, citations, executed, guardrail.getRewrittenAnswer(),
                AgentResult.Status.DONE, blockReason, null);
    }

    private Map<String, Object> executeWithTimeout(final AgentTool tool, final Map<String, Object> input) {
        Future<Map<String, Object>> future = toolExecutor.submit(new Callable<Map<String, Object>>() {
            public Map<String, Object> call() throws Exception {
                return tool.execute(input);
            }
        });
        try {
            Map<String, Object> output = future.get(STEP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return output == null ? errorOutput("EMPTY_TOOL_OUTPUT", "工具未返回结果") : output;
        } catch (TimeoutException error) {
            future.cancel(true);
            return errorOutput("STEP_TIMEOUT", "工具执行超时（" + STEP_TIMEOUT_MS + "ms）");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return errorOutput("INTERRUPTED", "工具执行被中断");
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            String message = cause == null ? "工具执行失败" : String.valueOf(cause.getMessage());
            return errorOutput("TOOL_ERROR", message);
        }
    }

    private Map<String, Object> errorOutput(String code, String message) {
        Map<String, Object> output = new LinkedHashMap<String, Object>();
        output.put("error", code + ": " + message);
        output.put("lowScore", Boolean.TRUE);
        return output;
    }

    private AgentResult finish(List<AgentStepEvent> events, Consumer<AgentStepEvent> sink,
                               List<Map<String, Object>> citations, int executed, String answer,
                               AgentResult.Status status, String reason, String overrideAnswer) {
        List<Map<String, Object>> kept = new ArrayList<Map<String, Object>>();
        if (status == AgentResult.Status.DONE) {
            kept.addAll(citations);
        }
        String finalAnswer = overrideAnswer != null ? overrideAnswer : (answer == null ? REFUSAL_ANSWER : answer);
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("status", status.name());
        payload.put("reason", reason);
        payload.put("citationCount", Integer.valueOf(kept.size()));
        payload.put("steps", Integer.valueOf(executed));
        AgentStepEvent finalEvent = new AgentStepEvent("final", executed, null, finalAnswer, payload);
        events.add(finalEvent);
        if (sink != null) {
            sink.accept(finalEvent);
        }
        return new AgentResult(finalAnswer, kept, events, executed, status);
    }

    /**
     * 从模型输出里取出动作 JSON。
     *
     * <p>真实模型（尤其思考模式）常把 JSON 包在 ```json 围栏里，或在前面加一句"好的，下一步："。
     * 原来直接 {@code parseObject(raw.trim())} 会把这类输出整体判为不可解析——接入真实模型后
     * 这是最容易出现的"智能体一步不动"故障。这里改为扫描第一个**花括号配对完整**的对象，
     * 且扫描时跳过字符串字面量内的括号，避免 {@code {"input":{"q":"a{b"}}} 这类内容被截断。</p>
     */
    private JSONObject parsePlan(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        if (text.isEmpty()) {
            return null;
        }
        int start = text.indexOf('{');
        while (start >= 0) {
            int end = matchingBrace(text, start);
            if (end > start) {
                JSONObject object = tryParse(text.substring(start, end + 1));
                if (object != null && object.containsKey("tool")) {
                    return object;
                }
            }
            start = text.indexOf('{', start + 1);
        }
        return null;
    }

    /**
     * 用作答阶段的系统提示替换规划阶段的提示，其余对话（含证据块）原样保留。
     */
    private List<Map<String, Object>> composeHistory(List<Map<String, Object>> history) {
        List<Map<String, Object>> copy = new ArrayList<Map<String, Object>>(history);
        if (!copy.isEmpty() && "system".equals(copy.get(0).get("role"))) {
            copy.set(0, message("system", ANSWER_SYSTEM_PROMPT));
        }
        return copy;
    }

    /**
     * 在作答提示之后追加一条"上次被守门拦下"的说明，用于重写。措辞直接点名禁止的表述，
     * 因为实测模型对抽象约束（"不得声称已执行"）遵守不稳，对具体禁用词更敏感。
     */
    private List<Map<String, Object>> composeHistoryAfterExecutionClaim(List<Map<String, Object>> history) {
        List<Map<String, Object>> copy = composeHistory(history);
        copy.add(message("user", "上一次回答里出现了表示设备动作已完成的表述，已被安全规则拦下。请重新作答："
                + "只给决策建议，禁止出现任何完成态表述（如“已自动”“已经自动”“已开启”“已执行”“已启动”“已替你”）；"
                + "需要执行时写成“建议……，由操作人员确认后在平台上执行”。"));
        return copy;
    }

    /**
     * 兜底解包：模型偶尔仍会把答案包成 {@code {"tool":"FINALIZE","input":{"answer":"…"}}}。
     *
     * <p>只有"整段几乎就是一个 JSON 对象"时才拆（前后残留不超过 8 个字符），
     * 以免误伤正文里本来含花括号的正常回答。若对象里既没有动作也没有答案，返回 null 交给拒答兜底。</p>
     */
    private String unwrapAnswer(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        if (text.isEmpty()) {
            return text;
        }
        String candidate = null;
        if (text.startsWith("{")) {
            candidate = text;
        } else {
            int start = text.indexOf('{');
            if (start < 0) {
                return raw;
            }
            int end = matchingBrace(text, start);
            boolean jsonDominates = end > start && start <= 8 && text.length() - (end + 1) <= 8;
            if (!jsonDominates) {
                return raw;
            }
            candidate = text.substring(start, end + 1);
        }
        try {
            JSONObject object = JSON.parseObject(candidate);
            if (object == null) {
                return raw;
            }
            JSONObject input = object.getJSONObject("input");
            JSONObject source = input == null ? object : input;
            for (String key : new String[]{"answer", "content", "text", "reply"}) {
                String value = source.getString(key);
                if (value != null && !value.trim().isEmpty()) {
                    return value;
                }
            }
            return object.containsKey("tool") ? null : raw;
        } catch (RuntimeException error) {
            return raw;
        }
    }

    /** 返回与 {@code start} 处 '{' 配对的 '}' 下标；不配对时返回 -1。字符串字面量内的括号不算数。 */
    private int matchingBrace(String text, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = start; index < text.length(); index++) {
            char current = text.charAt(index);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }
            if (current == '"') {
                inString = true;
            } else if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0) {
                    return index;
                }
            }
        }
        return -1;
    }

    private JSONObject tryParse(String candidate) {
        try {
            return JSON.parseObject(candidate);
        } catch (RuntimeException error) {
            return null;
        }
    }

    private Map<String, Object> toMap(JSONObject object) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (object != null) {
            for (String key : object.keySet()) {
                result.put(key, object.get(key));
            }
        }
        return result;
    }

    private Map<String, Object> message(String role, String content) {
        Map<String, Object> message = new LinkedHashMap<String, Object>();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    private String summarize(String toolName, int citationCount, long durationMs, Map<String, Object> output) {
        if (output.get("error") != null) {
            return toolName + " 执行失败：" + output.get("error");
        }
        return toolName + " 命中 " + citationCount + " 条，耗时 " + durationMs + "ms"
                + (Boolean.TRUE.equals(output.get("degraded")) ? "（降级：仅关键词检索）" : "")
                + (Boolean.TRUE.equals(output.get("lowScore")) ? "（相关性不足）" : "");
    }

    private String systemPrompt() {
        return "你是面向番茄设施种植的农业智能体。可用工具目录：" + registry.catalogJson()
                + "。每一步只输出一个 JSON：{\"tool\":\"工具名\",\"input\":{...}}；"
                + "没有可用工具或信息已足够时输出 {\"tool\":\"FINALIZE\",\"input\":{}}。"
                + "禁止输出 JSON 以外的内容。不得编造工具名。"
                // 实测：同一问题、同一提示，模型自选查询词不同会导致漏检（一次检索漏掉"同心轮纹"型病斑，
                // 结论退化成"无法确诊"；分两次检索则命中）。因此显式要求按特征分步检索。
                + "检索时：query 要包含用户描述中的具体症状词（如病斑形状、颜色、部位、扩展速度），"
                + "不要只用一个宽泛词；当描述包含多个特征时，应分步检索不同特征再汇总，不要只检索一次。";
    }

    private String userPrompt(String question, String crop) {
        return "用户问题：" + (question == null ? "" : question)
                + (crop == null || crop.trim().isEmpty() ? "" : "；作物：" + crop);
    }
}
