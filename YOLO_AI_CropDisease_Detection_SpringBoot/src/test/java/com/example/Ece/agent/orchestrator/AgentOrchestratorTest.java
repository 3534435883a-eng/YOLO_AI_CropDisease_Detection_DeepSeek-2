package com.example.Ece.agent.orchestrator;

import com.example.Ece.agent.rag.CitationFormatter;
import com.example.Ece.agent.rag.EmbeddingClient;
import com.example.Ece.agent.rag.KnowledgeChunk;
import com.example.Ece.agent.rag.KnowledgeRetriever;
import com.example.Ece.agent.tool.AgentToolRegistry;
import com.example.Ece.agent.tool.KnowledgeSearchTool;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentOrchestratorTest {

    private AgentToolRegistry registry() {
        return registryWith(Arrays.asList(
                new KnowledgeChunk("disease", 1L, "番茄", "早疫病",
                        KnowledgeChunk.FieldType.SYMPTOM, 0, 0, "番茄叶片出现褐色轮纹斑", "h1")));
    }

    private AgentToolRegistry registryWith(List<KnowledgeChunk> corpus) {
        KnowledgeRetriever retriever = new KnowledgeRetriever(new EmbeddingClient() {
            public double[] embed(String text) {
                return new double[]{1.0, 0.0};
            }
        });
        retriever.rebuild(corpus);
        AgentToolRegistry registry = new AgentToolRegistry();
        registry.register(new KnowledgeSearchTool(retriever, new CitationFormatter()));
        return registry;
    }

    @Test
    void runsToolThenFinalizes() {
        final int[] planCalls = {0};
        LlmClient llm = new LlmClient() {
            public String plan(List<Map<String, Object>> history) {
                planCalls[0]++;
                if (planCalls[0] == 1) {
                    return "{\"tool\":\"knowledge.search\",\"input\":{\"query\":\"褐色轮纹斑\"}}";
                }
                return "{\"tool\":\"FINALIZE\",\"input\":{}}";
            }

            public String compose(List<Map<String, Object>> history) {
                return "疑似早疫病，请结合田间情况复核。[1]";
            }
        };
        AgentOrchestrator orchestrator = new AgentOrchestrator(registry(), llm);
        List<AgentStepEvent> events = new ArrayList<AgentStepEvent>();
        AgentResult result = orchestrator.run("s1", "叶子有褐色轮纹斑", "番茄", new Consumer<AgentStepEvent>() {
            public void accept(AgentStepEvent event) {
                events.add(event);
            }
        });
        assertEquals("疑似早疫病，请结合田间情况复核。[1]", result.getAnswer());
        assertTrue(events.stream().anyMatch(e -> "knowledge.search".equals(e.getToolName())));
        assertTrue(events.stream().anyMatch(e -> "final".equals(e.getType())));
        assertFalse(result.getCitations().isEmpty());
        assertEquals(AgentResult.Status.DONE, result.getStatus());
    }

    @Test
    void stopsAtMaxStepsAndRefusesWhenNoEvidence() {
        LlmClient looping = new LlmClient() {
            public String plan(List<Map<String, Object>> history) {
                return "{\"tool\":\"knowledge.search\",\"input\":{\"query\":\"完全不相关的问题\"}}";
            }

            public String compose(List<Map<String, Object>> history) {
                return "无依据";
            }
        };
        AgentOrchestrator orchestrator = new AgentOrchestrator(registry(), looping);
        AgentResult result = orchestrator.run("s2", "量子计算机", "番茄", null);
        assertTrue(result.getSteps() <= AgentOrchestrator.MAX_STEPS);
        assertEquals(AgentResult.Status.REFUSED, result.getStatus());
    }

    @Test
    void renumbersAndDeduplicatesCitationsAcrossToolCalls() {
        AgentToolRegistry registry = registryWith(Arrays.asList(
                new KnowledgeChunk("disease", 1L, "番茄", "早疫病",
                        KnowledgeChunk.FieldType.SYMPTOM, 0, 0, "番茄叶片出现褐色轮纹斑", "h1"),
                new KnowledgeChunk("disease", 2L, "番茄", "灰霉病",
                        KnowledgeChunk.FieldType.SYMPTOM, 0, 0, "番茄果实表面出现白色霉层", "h2")));
        final int[] planCalls = {0};
        LlmClient llm = new LlmClient() {
            public String plan(List<Map<String, Object>> history) {
                planCalls[0]++;
                if (planCalls[0] == 1) {
                    return "{\"tool\":\"knowledge.search\",\"input\":{\"query\":\"褐色轮纹斑\"}}";
                }
                if (planCalls[0] == 2) {
                    return "{\"tool\":\"knowledge.search\",\"input\":{\"query\":\"白色霉层\"}}";
                }
                return "{\"tool\":\"FINALIZE\",\"input\":{}}";
            }

            public String compose(List<Map<String, Object>> history) {
                return "综合结论。[1][2]";
            }
        };
        AgentOrchestrator orchestrator = new AgentOrchestrator(registry, llm);
        AgentResult result = orchestrator.run("s3", "叶片和果实都有异常", "番茄", null);

        List<Map<String, Object>> citations = result.getCitations();
        assertTrue(citations.size() >= 2, "two distinct diseases should be cited");
        Set<Object> indices = new HashSet<Object>();
        for (int i = 0; i < citations.size(); i++) {
            assertEquals(i + 1, citations.get(i).get("index"), "citation index must be连续且从 1 开始");
            assertTrue(indices.add(citations.get(i).get("index")), "citation index must be unique");
        }
        Set<Object> chunkKeys = new HashSet<Object>();
        for (Map<String, Object> citation : citations) {
            String key = citation.get("sourceId") + "|" + citation.get("fieldType") + "|" + citation.get("chunkNo");
            assertTrue(chunkKeys.add(key), "同一知识块不得重复引用");
        }
    }

    @Test
    void boundsPlanningStepsWhenToolIsUnknown() {
        final int[] planCalls = {0};
        LlmClient hallucinating = new LlmClient() {
            public String plan(List<Map<String, Object>> history) {
                planCalls[0]++;
                return "{\"tool\":\"no.such.tool\",\"input\":{}}";
            }

            public String compose(List<Map<String, Object>> history) {
                return "不应被采纳";
            }
        };
        AgentOrchestrator orchestrator = new AgentOrchestrator(registry(), hallucinating);
        AgentResult result = orchestrator.run("s4", "叶片异常", "番茄", null);
        assertTrue(planCalls[0] <= AgentOrchestrator.MAX_STEPS,
                "无效规划也必须受步数上限约束，否则会空转到超时");
        assertEquals(0, result.getSteps());
        assertEquals(AgentResult.Status.REFUSED, result.getStatus());
    }


    @Test
    void refusesAnswerThatClaimsAutomaticDeviceExecution() {
        LlmClient llm = new LlmClient() {
            public String plan(List<Map<String, Object>> history) {
                return "{\"tool\":\"knowledge.search\",\"input\":{\"query\":\"褐色轮纹斑\"}}";
            }

            public String compose(List<Map<String, Object>> history) {
                return "已自动开启通风设备，棚内湿度已降低。[1]";
            }
        };
        AgentOrchestrator orchestrator = new AgentOrchestrator(registry(), llm);
        AgentResult result = orchestrator.run("s5", "叶子有褐色轮纹斑", "番茄", null);
        assertEquals(AgentResult.Status.REFUSED, result.getStatus());
        assertEquals(AgentOrchestrator.REFUSAL_ANSWER, result.getAnswer());

        String reason = null;
        for (AgentStepEvent event : result.getEvents()) {
            if ("final".equals(event.getType())) {
                reason = String.valueOf(event.getPayload().get("reason"));
            }
        }
        assertEquals("AUTO_EXECUTION_CLAIM", reason, "守门层必须拦下越权的设备执行声明");
    }
}
