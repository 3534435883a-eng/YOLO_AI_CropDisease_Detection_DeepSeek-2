package com.example.Ece.agent.plan;

import com.example.Ece.config.AgriPlanProperties;
import com.example.Ece.dto.ai.AiChatResponse;
import com.example.Ece.dto.ai.ChatMessage;
import com.example.Ece.service.DeepSeekException;
import com.example.Ece.service.DeepSeekService;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 推演主链路的不变量测试。
 *
 * <p>这些断言盯的是**这条通道对用户的承诺**，不是实现细节：声明必须在、结构化块要么解析出来
 * 要么如实标没解析出来、降级必须被标注、取消必须真的中止而不是继续计费。</p>
 */
class PlanDeductionServiceTest {

    private static final String GOOD_TAIL =
            "\n```json\n{\"conclusion\":\"控湿为先\",\"actions\":[{\"stage\":\"坐果期\","
                    + "\"action\":\"上午通风降湿\",\"trigger\":\"湿度>85%\",\"detail\":\"降至75%以下\"}],"
                    + "\"cautions\":[\"用药需人工确认\"]}\n```\n";

    @Test
    void injectsBannerWhenModelOmitsIt() {
        DeepSeekService llm = llmReturning("## 一、农情判读\n\n湿度是主要限制因子。" + GOOD_TAIL);

        DeductionResult result = deduce(llm, emptySituation(), true);

        assertTrue(result.isBannerInjected(), "模型没写声明时，服务端必须补上");
        assertTrue(result.getMarkdown().startsWith(DeductionPrompts.SIMULATION_BANNER),
                "声明必须在首行；实际：" + firstLine(result.getMarkdown()));
    }

    @Test
    void keepsModelBannerWithoutDuplicatingIt() {
        DeepSeekService llm = llmReturning(
                DeductionPrompts.SIMULATION_BANNER + "\n\n## 一、农情判读\n\n正文。" + GOOD_TAIL);

        DeductionResult result = deduce(llm, emptySituation(), true);

        assertFalse(result.isBannerInjected(), "模型已经写了，不该记为服务端补的");
        assertEquals(1, countOccurrences(result.getMarkdown(), DeductionPrompts.SIMULATION_BANNER),
                "声明不能出现两次");
    }

    @Test
    void parsesStructuredActionsFromFencedBlock() {
        DeepSeekService llm = llmReturning("## 一、农情判读\n\n正文。" + GOOD_TAIL);

        DeductionResult result = deduce(llm, emptySituation(), true);

        assertNotNull(result.getStructuredJson(), "围栏里的 JSON 必须被解析出来");
        JSONObject structured = JSONObject.parseObject(result.getStructuredJson());
        assertEquals("控湿为先", structured.getString("conclusion"));
        assertEquals(1, structured.getJSONArray("actions").size());
        // 正文里不该再留着原始 JSON——结构化部分在前端单独渲染，留着会重复显示一遍。
        assertFalse(result.getMarkdown().contains("\"conclusion\""),
                "正文里不应残留结构化 JSON");
    }

    @Test
    void reportsMissingStructureWithoutInventingIt() {
        DeepSeekService llm = llmReturning("## 一、农情判读\n\n只有正文，没有 JSON 块。");

        DeductionResult result = deduce(llm, emptySituation(), true);

        assertNull(result.getStructuredJson(), "模型没给就不能替它编一份");
        assertEquals(1, result.getSections().size());
    }

    @Test
    void forwardsReasoningAndContentDeltasWithFlag() {
        DeepSeekService llm = mock(DeepSeekService.class);
        when(llm.chatStream(anyList(), any(DeepSeekService.ChatOptions.class),
                any(DeepSeekService.DeltaConsumer.class)))
                .thenAnswer(invocation -> {
                    DeepSeekService.DeltaConsumer consumer = invocation.getArgument(2);
                    consumer.accept("我在想湿度…", true);
                    consumer.accept("## 一、农情判读\n\n正文。" + GOOD_TAIL, false);
                    return "## 一、农情判读\n\n正文。" + GOOD_TAIL;
                });

        List<String> reasoning = new ArrayList<String>();
        List<String> content = new ArrayList<String>();
        deduceWithSink(llm, event -> {
            if (DeductionEvent.TYPE_DELTA.equals(event.getType())) {
                (Boolean.TRUE.equals(event.getPayload().get("reasoning")) ? reasoning : content)
                        .add(String.valueOf(event.getPayload().get("text")));
            }
        });

        assertEquals(1, reasoning.size(), "思考增量应单独一路");
        assertEquals(1, content.size(), "正文增量应单独一路");
        assertTrue(content.get(0).contains("农情判读"));
    }

    @Test
    void truncationResetsStreamThenRetriesWithoutThinking() {
        DeepSeekService llm = mock(DeepSeekService.class);
        when(llm.chatStream(anyList(), any(DeepSeekService.ChatOptions.class),
                any(DeepSeekService.DeltaConsumer.class)))
                .thenThrow(new DeepSeekException(DeepSeekException.CODE_OUTPUT_TRUNCATED, "截断了"));
        when(llm.chatWithStreamingTimeout(anyList(), any(DeepSeekService.ChatOptions.class)))
                .thenReturn(new AiChatResponse("## 一、农情判读\n\n重试后的完整正文。" + GOOD_TAIL, "deepseek-flash"));

        List<String> eventTypes = new ArrayList<String>();
        DeductionResult result = deduceWithSink(llm, event -> eventTypes.add(event.getType()));

        // reset 必须在补发正文之前：否则前端会把半截正文和新正文拼在一起。
        assertTrue(eventTypes.indexOf(DeductionEvent.TYPE_RESET) >= 0, "截断后必须先发 reset");
        assertEquals(DeductionResult.StreamMode.FALLBACK, result.getStreamMode());
        assertNotNull(result.getFallbackReason(), "降级原因要如实带上");
        assertTrue(result.getMarkdown().contains("重试后的完整正文"));
    }

    @Test
    void unwiredStreamingFallsBackAndSaysSo() {
        DeepSeekService llm = mock(DeepSeekService.class);
        when(llm.chatStream(anyList(), any(DeepSeekService.ChatOptions.class),
                any(DeepSeekService.DeltaConsumer.class)))
                .thenThrow(new DeepSeekException(DeepSeekException.CODE_STREAM_UNAVAILABLE, "未接线"));
        when(llm.chatWithStreamingTimeout(anyList(), any(DeepSeekService.ChatOptions.class)))
                .thenReturn(new AiChatResponse("## 一、农情判读\n\n整段返回的正文。" + GOOD_TAIL, "deepseek-flash"));

        DeductionResult result = deduce(llm, emptySituation(), true);

        // 把降级结果当正常结果下发，用户就无从判断为什么这次没有逐字出现。
        assertEquals(DeductionResult.StreamMode.FALLBACK, result.getStreamMode());
        assertTrue(result.getFallbackReason().contains("流式"));
    }

    @Test
    void cancellationAbortsAndThrowsInsteadOfReturningHalfAnAnswer() {
        DeepSeekService llm = mock(DeepSeekService.class);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        when(llm.chatStream(anyList(), any(DeepSeekService.ChatOptions.class),
                any(DeepSeekService.DeltaConsumer.class)))
                .thenAnswer(invocation -> {
                    DeepSeekService.DeltaConsumer consumer = invocation.getArgument(2);
                    consumer.accept("半截正文", false);
                    // 模拟用户在推演中途关掉页面
                    cancelled.set(true);
                    consumer.accept("更多正文", false);
                    return "半截正文更多正文";
                });

        // 取消后返回的是**半截正文**，看起来与完整回答无异——必须抛而不是返回。
        assertThrows(DeductionCancelledException.class,
                () -> deduceWithCancel(llm, cancelled));
    }

    @Test
    void cancellationSignalStopsTheUpstreamConsumer() {
        DeepSeekService llm = mock(DeepSeekService.class);
        AtomicBoolean cancelled = new AtomicBoolean(true);
        final boolean[] consumerReturned = new boolean[1];
        when(llm.chatStream(anyList(), any(DeepSeekService.ChatOptions.class),
                any(DeepSeekService.DeltaConsumer.class)))
                .thenAnswer(invocation -> {
                    DeepSeekService.DeltaConsumer consumer = invocation.getArgument(2);
                    // 已取消时，消费方必须返回 false——这是"关闭上游连接"的唯一途径。
                    consumerReturned[0] = consumer.accept("文本", false);
                    return "文本";
                });

        assertThrows(DeductionCancelledException.class, () -> deduceWithCancel(llm, cancelled));
        assertFalse(consumerReturned[0], "取消后消费方必须返回 false，否则上游会继续生成、继续计费");
    }

    @Test
    void reportsMissingSituationFieldsInsteadOfAssumingThem() {
        DeepSeekService llm = llmReturning("## 一、农情判读\n\n正文。" + GOOD_TAIL);
        AgriSituationInput situation = new AgriSituationInput();
        situation.put("crop", "番茄");
        situation.put("temperatureC", "25℃");

        List<String> capturedPrompt = new ArrayList<String>();
        when(llm.chatStream(anyList(), any(DeepSeekService.ChatOptions.class),
                any(DeepSeekService.DeltaConsumer.class)))
                .thenAnswer(invocation ->
                        capturePromptAndReturn(invocation.getArgument(0), capturedPrompt,
                                "## 一、农情判读\n\n正文。" + GOOD_TAIL));

        DeductionResult result = deduce(llm, situation, true);

        // 缺失项要进提示词：模型看不到"没给什么"时，会默认那些量是正常的。
        assertTrue(capturedPrompt.get(0).contains("用户未提供的项"),
                "提示词里必须显式列出未提供的字段");
        assertFalse(result.getMissingFields().isEmpty());
        assertFalse(result.getMissingFields().contains("作物"), "给了的字段不该算缺失");
    }

    /* ------------------------------ 辅助 ------------------------------ */

    private DeductionResult deduce(DeepSeekService llm, AgriSituationInput situation,
                                   boolean cancelled) {
        return deduceWithCancel(llm, situation, new AtomicBoolean(!cancelled));
    }

    private DeductionResult deduceWithCancel(DeepSeekService llm, AtomicBoolean cancelled) {
        return deduceWithCancel(llm, emptySituation(), cancelled);
    }

    private DeductionResult deduceWithCancel(DeepSeekService llm, AgriSituationInput situation,
                                             AtomicBoolean cancelled) {
        return service(llm).deduce(situation, null, null, null, null, cancelled::get);
    }

    private DeductionResult deduceWithSink(DeepSeekService llm,
                                           java.util.function.Consumer<DeductionEvent> sink) {
        return service(llm).deduce(emptySituation(), null, null, null, sink,
                new AtomicBoolean(false)::get);
    }

    private PlanDeductionService service(DeepSeekService llm) {
        AgriPlanBaseline baseline = mock(AgriPlanBaseline.class);
        when(baseline.compute(anyLong(), anyInt()))
                .thenReturn(AgriPlanBaseline.Baseline.unavailable(1L, 120, "测试用：无基线"));
        return new PlanDeductionService(llm, baseline, new AgriPlanProperties());
    }

    private AgriSituationInput emptySituation() {
        return new AgriSituationInput();
    }

    private DeepSeekService llmReturning(String raw) {
        DeepSeekService llm = mock(DeepSeekService.class);
        when(llm.chatStream(anyList(), any(DeepSeekService.ChatOptions.class),
                any(DeepSeekService.DeltaConsumer.class)))
                .thenReturn(raw);
        return llm;
    }

    private String capturePromptAndReturn(List<ChatMessage> messages, List<String> captured,
                                          String raw) {
        captured.add(messages.get(1).getContent());
        return raw;
    }

    private int countOccurrences(String text, String needle) {
        int count = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            count++;
            index = text.indexOf(needle, index + needle.length());
        }
        return count;
    }

    private String firstLine(String text) {
        int newline = text.indexOf('\n');
        return newline < 0 ? text : text.substring(0, newline);
    }
}
