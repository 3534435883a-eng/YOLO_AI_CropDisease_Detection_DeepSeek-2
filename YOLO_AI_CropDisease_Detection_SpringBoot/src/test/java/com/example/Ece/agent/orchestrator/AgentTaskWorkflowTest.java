package com.example.Ece.agent.orchestrator;

import com.example.Ece.agent.m3.M3LiveService;
import com.example.Ece.agent.task.FarmTaskService;
import com.example.Ece.agent.tool.AgentToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentTaskWorkflowTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    @SuppressWarnings("unchecked")
    private FarmTaskService tasks(M3LiveService live) {
        ObjectProvider<M3LiveService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(live);
        return new FarmTaskService(mapper, provider, directory.toString());
    }

    @Test
    void savesFrozenQuestionAndAnswerBeforeFinalEventAndRetryKeepsOneArchivedPair() throws Exception {
        M3LiveService live = mock(M3LiveService.class);
        FarmTaskService tasks = tasks(live);
        String runId = UUID.randomUUID().toString();
        String taskId = tasks.create(mapper.createObjectNode().put("simulationRunId", runId).put("question", "连续阴雨，叶片有斑")).path("id").asText();
        ObjectNode run = mapper.createObjectNode().put("runId", runId).put("cursor", 2);
        run.putObject("current").put("at", "2025-03-06T12:00").putObject("scenario").put("temperatureC", 23);
        when(live.current(runId, true)).thenReturn(run);
        List<List<Map<String, Object>>> prompts = new ArrayList<>();
        LlmClient llm = new LlmClient() {
            private int answers;
            public String plan(List<Map<String, Object>> history) { prompts.add(new ArrayList<>(history)); return "{\"tool\":\"FINALIZE\",\"input\":{}}"; }
            public String compose(List<Map<String, Object>> history) { return ++answers == 1 ? "先补拍叶片近照，结合当前湿度安排通风。" : "这是重复请求的新回答。"; }
        };
        AgentOrchestrator agent = new AgentOrchestrator(new AgentToolRegistry(), llm);
        ReflectionTestUtils.setField(agent, "farmTasks", tasks);
        String requestId = UUID.randomUUID().toString();
        List<String> events = new ArrayList<>();
        try {
            AgentResult answer = agent.run("case-session", "怎么管理", "番茄", runId, null, taskId, requestId, false, () -> false, event -> {
                events.add(event.getType());
                if ("final".equals(event.getType())) {
                    try { assertEquals(2, tasks.read(taskId).path("turns").size(), "final 下发前正文必须进入任务档案"); }
                    catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
                }
            });
            assertEquals(AgentResult.Status.DONE, answer.getStatus());
            assertTrue(events.indexOf("task") < events.indexOf("final"));
            ObjectNode first = tasks.read(taskId);
            assertEquals(runId, first.path("turns").get(1).path("context").path("simulationRunId").asText());
            assertEquals("2025-03-06T12:00", first.path("turns").get(1).path("context").path("current").path("at").asText());
            run.path("current").deepCopy();
            ((ObjectNode) run.path("current")).put("at", "2025-03-07T12:00");
            agent.run("case-session", "怎么管理", "番茄", runId, null, taskId, requestId, false, () -> false, null);
            assertEquals(first.path("turns"), tasks.read(taskId).path("turns"), "同 requestId 重试不能覆盖第一次的回答和冻结状态");
            assertTrue(prompts.get(0).toString().contains("连续阴雨"));
        } finally { agent.shutdown(); }
    }

    @Test
    void sameClientSessionCannotCarryAnswerAcrossDifferentTasksOrIndependentChat() throws Exception {
        M3LiveService live = mock(M3LiveService.class);
        FarmTaskService tasks = tasks(live);
        String first = tasks.create(mapper.createObjectNode().put("question", "任务一特有农情")).path("id").asText();
        String second = tasks.create(mapper.createObjectNode().put("question", "任务二特有农情")).path("id").asText();
        List<String> planning = new ArrayList<>();
        LlmClient llm = new LlmClient() {
            public String plan(List<Map<String, Object>> history) { planning.add(history.toString()); return "{\"tool\":\"FINALIZE\",\"input\":{}}"; }
            public String compose(List<Map<String, Object>> history) { return history.toString().contains("任务一特有农情") ? "第一任务专属回答" : "一般农业解释"; }
        };
        AgentOrchestrator agent = new AgentOrchestrator(new AgentToolRegistry(), llm);
        ReflectionTestUtils.setField(agent, "farmTasks", tasks);
        try {
            agent.run("same-ui-session", "第一次的问题", "番茄", null, null, first, null, false, () -> false, null);
            agent.run("same-ui-session", "另一任务的问题", "番茄", null, null, second, null, false, () -> false, null);
            agent.run("same-ui-session", "独立一般提问", "番茄", null);
            assertFalse(planning.get(1).contains("第一任务专属回答"));
            assertFalse(planning.get(1).contains("任务一特有农情"));
            assertFalse(planning.get(2).contains("任务一特有农情"));
            assertFalse(planning.get(2).contains("任务二特有农情"));
            verifyNoInteractions(live);
        } finally { agent.shutdown(); }
    }
}
