package com.example.Ece.agent.plan;

import com.example.Ece.agent.m3.M3LiveService;
import com.example.Ece.agent.task.FarmTaskService;
import com.example.Ece.config.AgriPlanProperties;
import com.example.Ece.dto.ai.ChatMessage;
import com.example.Ece.service.DeepSeekService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LinkedPlanWorkflowTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String OUTPUT = "按当前农情先检查病叶并控制夜间叶面湿润。\n```json\n{\"conclusion\":\"先核查再调整\",\"actions\":[{\"action\":\"复查病叶\",\"stage\":\"坐果期\",\"detail\":\"补拍病斑近照\",\"trigger\":\"次日查看新病斑\"}],\"cautions\":[\"症状需确认\"]}\n```";

    @SuppressWarnings("unchecked")
    private FarmTaskService tasks() {
        ObjectProvider<M3LiveService> provider = mock(ObjectProvider.class);
        return new FarmTaskService(mapper, provider, directory.toString());
    }

    @Test
    void linkedPlanUsesTaskEvidenceDoesNotComputeLegacyBaselineAndSavesActionsBeforeFinal() throws Exception {
        FarmTaskService tasks = tasks();
        String id = tasks.create(mapper.createObjectNode().put("question", "连续阴雨，叶片有斑")).path("id").asText();
        tasks.addEvidence(id, mapper.createObjectNode().put("label", "叶片照片").put("imageUrl", "/files/leaf.jpg"));
        AgriPlanBaseline baseline = mock(AgriPlanBaseline.class);
        DeepSeekService llm = mock(DeepSeekService.class);
        List<String> prompts = new ArrayList<>();
        when(llm.chatStream(anyList(), any(DeepSeekService.ChatOptions.class), any(DeepSeekService.DeltaConsumer.class)))
                .thenAnswer(invocation -> { List<ChatMessage> messages = invocation.getArgument(0); for (ChatMessage message : messages) prompts.add(message.getContent()); return OUTPUT; });
        PlanDeductionService service = new PlanDeductionService(llm, baseline, new AgriPlanProperties());
        ReflectionTestUtils.setField(service, "farmTasks", tasks);
        List<String> events = new ArrayList<>();
        DeductionResult result = service.deduce(new AgriSituationInput(), null, null, null, id, null, UUID.randomUUID().toString(), event -> {
            events.add(event.getType());
            if (DeductionEvent.TYPE_FINAL.equals(event.getType())) {
                try { assertEquals(1, tasks.read(id).path("actions").size()); }
                catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
            }
        }, () -> false);
        verifyNoInteractions(baseline);
        assertNotNull(result.getStructuredJson());
        assertTrue(prompts.toString().contains("叶片照片"));
        assertTrue(prompts.toString().contains("连续阴雨"));
        assertTrue(prompts.get(0).contains("数值不同不等于测点矛盾"));
        assertTrue(prompts.get(0).contains("不是感染阈值"));
        assertTrue(prompts.get(0).contains("不自行给水肥剂量"));
        assertFalse(prompts.get(0).contains("七节都要有"));
        ObjectNode task = tasks.read(id);
        assertEquals(2, task.path("turns").size());
        assertEquals("HUMAN", task.path("actions").get(0).path("type").asText());
        assertEquals("PENDING", task.path("actions").get(0).path("status").asText());
        assertEquals("次日查看新病斑", task.path("actions").get(0).path("reviewCondition").asText());
        assertTrue(task.path("turns").get(1).has("context"));
        assertEquals("DONE", task.path("turns").get(1).path("status").asText());
        assertTrue(events.indexOf("task") < events.indexOf(DeductionEvent.TYPE_FINAL));
    }

    @Test
    void bulkyLinkedSceneIsSummarizedForModelWithoutChangingFrozenArchive() throws Exception {
        FarmTaskService tasks = mock(FarmTaskService.class);
        ObjectNode context = mapper.createObjectNode();
        context.putObject("task").put("question", "连续阴雨，叶片有斑");
        ObjectNode current = context.putObject("current"); current.put("at", "2025-04-19T12:00");
        current.putArray("plants").add(String.join("", Collections.nCopies(30000, "株")));
        current.putObject("scenario").put("temperatureC", 22).putArray("trends").add(String.join("", Collections.nCopies(30000, "轨")));
        ObjectNode original = context.deepCopy();
        when(tasks.context(null, "run")).thenReturn(context);
        DeepSeekService llm = mock(DeepSeekService.class);
        when(llm.chatStream(anyList(), any(DeepSeekService.ChatOptions.class), any(DeepSeekService.DeltaConsumer.class)))
                .thenAnswer(invocation -> {
                    List<ChatMessage> messages = invocation.getArgument(0);
                    for (ChatMessage message : messages) assertTrue(message.getContent().length() <= 12000);
                    assertTrue(messages.toString().contains("连续阴雨") || messages.stream().anyMatch(m -> m.getContent().contains("连续阴雨")));
                    return OUTPUT;
                });
        PlanDeductionService service = new PlanDeductionService(llm, mock(AgriPlanBaseline.class), new AgriPlanProperties());
        ReflectionTestUtils.setField(service, "farmTasks", tasks);
        service.deduce(new AgriSituationInput(), "如何管理", null, null, null, "run", null, null, () -> false);
        assertEquals(original, context);
    }

    @Test
    void cancelledLinkedPlanDoesNotSavePartialTurnsOrActions() throws Exception {
        FarmTaskService tasks = tasks();
        String id = tasks.create(mapper.createObjectNode().put("question", "规划管理")).path("id").asText();
        DeepSeekService llm = mock(DeepSeekService.class);
        AtomicBoolean cancelled = new AtomicBoolean();
        when(llm.chatStream(anyList(), any(DeepSeekService.ChatOptions.class), any(DeepSeekService.DeltaConsumer.class)))
                .thenAnswer(invocation -> { cancelled.set(true); return OUTPUT; });
        PlanDeductionService service = new PlanDeductionService(llm, mock(AgriPlanBaseline.class), new AgriPlanProperties());
        ReflectionTestUtils.setField(service, "farmTasks", tasks);
        assertThrows(DeductionCancelledException.class, () -> service.deduce(new AgriSituationInput(), "怎么管", null, null, id, null, null, null, cancelled::get));
        assertEquals(0, tasks.read(id).path("turns").size());
        assertEquals(0, tasks.read(id).path("actions").size());
    }
}
