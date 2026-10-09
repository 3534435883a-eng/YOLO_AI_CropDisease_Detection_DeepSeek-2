package com.example.Ece.agent.orchestrator;

import com.example.Ece.agent.guard.GuardrailService;
import com.example.Ece.agent.rag.CitationFormatter;
import com.example.Ece.agent.rag.EmbeddingClient;
import com.example.Ece.agent.rag.KnowledgeChunk;
import com.example.Ece.agent.rag.KnowledgeRetriever;
import com.example.Ece.agent.service.AgentChatHistoryService;
import com.example.Ece.agent.tool.AgentToolRegistry;
import com.example.Ece.agent.tool.KnowledgeSearchTool;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 会话历史持久化与编排的接缝测试。
 *
 * <p>核心不变量：**历史写失败不得改变回答**。历史是审计与评测的旁路，
 * MySQL 抖动或磁盘写不进去，用户仍然要拿到那条回答。</p>
 */
class AgentOrchestratorHistoryTest {

    private static final String QUESTION = "叶子有褐色轮纹斑";

    private AgentToolRegistry registry() {
        KnowledgeRetriever retriever = new KnowledgeRetriever(new EmbeddingClient() {
            public double[] embed(String text) {
                return new double[]{1.0, 0.0};
            }
        });
        retriever.rebuild(Arrays.asList(new KnowledgeChunk("disease", 1L, "番茄", "早疫病",
                KnowledgeChunk.FieldType.SYMPTOM, 0, 0, "番茄叶片出现褐色轮纹斑", "h1")));
        AgentToolRegistry registry = new AgentToolRegistry();
        registry.register(new KnowledgeSearchTool(retriever, new CitationFormatter()));
        return registry;
    }

    private LlmClient llm() {
        return new LlmClient() {
            private int planCalls = 0;

            public String plan(List<Map<String, Object>> history) {
                planCalls++;
                if (planCalls == 1) {
                    return "{\"tool\":\"knowledge.search\",\"input\":{\"query\":\"褐色轮纹斑\"}}";
                }
                return "{\"tool\":\"FINALIZE\",\"input\":{}}";
            }

            public String compose(List<Map<String, Object>> history) {
                return "疑似早疫病，请结合田间情况复核。[1]";
            }
        };
    }

    @Test
    void historyFailureDoesNotChangeTheAnswerOrStatus() {
        AgentChatHistoryService failing = mock(AgentChatHistoryService.class);
        doThrow(new IllegalStateException("history store down"))
                .when(failing).record(any(), any(), any(), any());
        AgentOrchestrator orchestrator = new AgentOrchestrator(registry(), llm(), new GuardrailService(), failing);

        AgentResult result = orchestrator.run("s-history-down", QUESTION, "番茄", null);

        assertEquals(AgentResult.Status.DONE, result.getStatus());
        assertEquals("疑似早疫病，请结合田间情况复核。[1]", result.getAnswer());
        assertTrue(result.getCitations().size() > 0, "引用不应因历史写失败而丢失");
    }

    @Test
    void historyServiceReceivesTheTerminalOutcome() {
        AgentChatHistoryService history = mock(AgentChatHistoryService.class);
        AgentOrchestrator orchestrator = new AgentOrchestrator(registry(), llm(), new GuardrailService(), history);

        orchestrator.run("s-record", QUESTION, "番茄", null);

        ArgumentCaptor<AgentResult> captor = ArgumentCaptor.forClass(AgentResult.class);
        verify(history).record(eq("s-record"), eq("番茄"), eq(QUESTION), captor.capture());
        AgentResult recorded = captor.getValue();
        assertNotNull(recorded);
        assertEquals(AgentResult.Status.DONE, recorded.getStatus());
        assertEquals("疑似早疫病，请结合田间情况复核。[1]", recorded.getAnswer());
    }

    @Test
    void refusedOutcomeIsAlsoRecorded() {
        AgentChatHistoryService history = mock(AgentChatHistoryService.class);
        // 无资料时可作一般解释，但虚称完成设备处置仍必须拒绝并保存拒答终态。
        LlmClient finalizeOnly = new LlmClient() {
            public String plan(List<Map<String, Object>> historyMessages) {
                return "{\"tool\":\"FINALIZE\",\"input\":{}}";
            }

            public String compose(List<Map<String, Object>> historyMessages) {
                return "已自动完成设备处置。";
            }
        };
        AgentOrchestrator orchestrator = new AgentOrchestrator(registry(), finalizeOnly, new GuardrailService(), history);

        AgentResult result = orchestrator.run("s-refused", QUESTION, "番茄", null);

        assertEquals(AgentResult.Status.REFUSED, result.getStatus());
        ArgumentCaptor<AgentResult> captor = ArgumentCaptor.forClass(AgentResult.class);
        verify(history).record(eq("s-refused"), eq("番茄"), eq(QUESTION), captor.capture());
        assertEquals(AgentResult.Status.REFUSED, captor.getValue().getStatus());
        assertTrue(captor.getValue().getAnswer() != null && !captor.getValue().getAnswer().isEmpty(),
                "拒答也必须有一条可读的回答正文，否则历史里是空的");
    }
}
