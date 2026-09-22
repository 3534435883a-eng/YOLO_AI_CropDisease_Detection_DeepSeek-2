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
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    /**
     * 真实模型（尤其思考模式）常把动作 JSON 包在 ```json 围栏里并带一句说明。
     * 原实现整体 trim 后解析，这类输出会被判 PLAN_UNPARSEABLE，表现为"智能体一步不动"。
     */
    @Test
    void parsesPlanWrappedInCodeFenceAndProse() {
        final int[] planCalls = {0};
        LlmClient llm = new LlmClient() {
            public String plan(List<Map<String, Object>> history) {
                planCalls[0]++;
                if (planCalls[0] == 1) {
                    return "好的，下一步应该先检索知识库：```json\n"
                            + "{\"tool\":\"knowledge.search\",\"input\":{\"query\":\"褐色轮纹斑\"}}\n```";
                }
                return "```json\n{\"tool\":\"FINALIZE\",\"input\":{}}\n```";
            }

            public String compose(List<Map<String, Object>> history) {
                return "疑似早疫病，请结合田间复核。[1]";
            }
        };
        AgentOrchestrator orchestrator = new AgentOrchestrator(registry(), llm);
        AgentResult result = orchestrator.run("s6", "叶子有褐色轮纹斑", "番茄", null);

        assertEquals(AgentResult.Status.DONE, result.getStatus(), "带围栏与前后缀的动作 JSON 必须能解析");
        assertFalse(result.getCitations().isEmpty());
    }

    /** 字符串字面量里的花括号不能把动作对象截断。 */
    @Test
    void parsesPlanContainingBracesInsideStrings() {
        final int[] planCalls = {0};
        LlmClient llm = new LlmClient() {
            public String plan(List<Map<String, Object>> history) {
                planCalls[0]++;
                if (planCalls[0] == 1) {
                    return "{\"tool\":\"knowledge.search\",\"input\":{\"query\":\"褐色轮纹斑\"},"
                            + "\"note\":\"参数模板 {a:{b}} 示例\"}";
                }
                return "{\"tool\":\"FINALIZE\",\"input\":{}}";
            }

            public String compose(List<Map<String, Object>> history) {
                return "疑似早疫病。[1]";
            }
        };
        AgentOrchestrator orchestrator = new AgentOrchestrator(registry(), llm);
        AgentResult result = orchestrator.run("s7", "叶子有褐色轮纹斑", "番茄", null);

        assertEquals(AgentResult.Status.DONE, result.getStatus());
        assertFalse(result.getCitations().isEmpty());
    }

    /**
     * 作答步必须换成"作答系统提示"。规划阶段的提示写着"只输出一个 JSON"，
     * 实测模型在作答步会照做，把答案包成 {"tool":"FINALIZE","input":{"answer":"…"}}。
     */
    @Test
    void usesAnsweringSystemPromptForComposeStep() {
        final String[] composeSystemPrompt = {null};
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
                if (!history.isEmpty() && "system".equals(history.get(0).get("role"))) {
                    composeSystemPrompt[0] = String.valueOf(history.get(0).get("content"));
                }
                return "疑似早疫病。[1]";
            }
        };
        AgentOrchestrator orchestrator = new AgentOrchestrator(registry(), llm);
        orchestrator.run("s9", "叶子有褐色轮纹斑", "番茄", null);

        assertNotNull(composeSystemPrompt[0], "作答步必须带上系统提示");
        assertFalse(composeSystemPrompt[0].contains("只输出一个 JSON"),
                "作答阶段不得沿用规划的 JSON 约束：" + composeSystemPrompt[0]);
        assertTrue(composeSystemPrompt[0].contains("作答"), "应换成作答阶段提示");
    }

    /** 模型仍把答案包进动作 JSON 时，必须解包后再返回，不能把 JSON 直接给用户。 */
    @Test
    void unwrapsAnswerWrappedInPlanJson() {
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
                return "{\"tool\":\"FINALIZE\",\"input\":{\"answer\":\"疑似早疫病，需结合田间复核。[1]\"}}";
            }
        };
        AgentOrchestrator orchestrator = new AgentOrchestrator(registry(), llm);
        AgentResult result = orchestrator.run("s10", "叶子有褐色轮纹斑", "番茄", null);

        assertEquals("疑似早疫病，需结合田间复核。[1]", result.getAnswer());
    }

    /** 正文里本来含花括号的散文不得被误当成 JSON 解包。 */
    @Test
    void keepsProseContainingBracesUnchanged() {
        final int[] planCalls = {0};
        String prose = "建议按 {温度, 湿度} 两个维度排查，疑似早疫病。[1]";
        LlmClient llm = new LlmClient() {
            public String plan(List<Map<String, Object>> history) {
                planCalls[0]++;
                if (planCalls[0] == 1) {
                    return "{\"tool\":\"knowledge.search\",\"input\":{\"query\":\"褐色轮纹斑\"}}";
                }
                return "{\"tool\":\"FINALIZE\",\"input\":{}}";
            }

            public String compose(List<Map<String, Object>> history) {
                return prose;
            }
        };
        AgentOrchestrator orchestrator = new AgentOrchestrator(registry(), llm);
        AgentResult result = orchestrator.run("s11", "叶子有褐色轮纹斑", "番茄", null);

        assertEquals(prose, result.getAnswer());
    }

    /**
     * 有证据但模型没给出回答时，必须如实报失败（REFUSED/ANSWER_EMPTY），
     * 不能用拒答文案兜底却报 DONE——界面上那会被显示成"结论"，把失败伪装成成功。
     */
    @Test
    void reportsAnswerEmptyInsteadOfFakingDone() {
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
                return null;
            }
        };
        AgentOrchestrator orchestrator = new AgentOrchestrator(registry(), llm);
        AgentResult result = orchestrator.run("s14", "叶子有褐色轮纹斑", "番茄", null);

        assertEquals(AgentResult.Status.REFUSED, result.getStatus());
        String reason = null;
        for (AgentStepEvent event : result.getEvents()) {
            if ("final".equals(event.getType())) {
                reason = String.valueOf(event.getPayload().get("reason"));
            }
        }
        assertEquals("ANSWER_EMPTY", reason);
    }

    /**
     * 作答被守门判为越权声明时应重写一次：实测同类问题 1/3 概率因模型顺手写"已自动…"而整段被丢成拒答。
     * 这里断言第二次作答被采纳，且回答里不再有完成态表述。
     */
    @Test
    void retriesComposeOnceAfterExecutionClaimIsBlocked() {
        final int[] composeCalls = {0};
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
                composeCalls[0]++;
                if (composeCalls[0] == 1) {
                    return "已自动开启通风设备，湿度已下降。[1]";
                }
                return "建议开启通风排湿，并清除病叶。[1]";
            }
        };
        AgentOrchestrator orchestrator = new AgentOrchestrator(registry(), llm);
        AgentResult result = orchestrator.run("s12", "叶子有褐色轮纹斑", "番茄", null);

        assertEquals(AgentResult.Status.DONE, result.getStatus(), "重写后应给出答案而不是拒答");
        assertEquals(2, composeCalls[0], "被拦下后应重写一次");
        assertFalse(result.getAnswer().contains("已自动"), "最终回答不得含完成态越权表述");
    }

    /** 重写仍越权时必须拒答——安全底线不因重试而放宽。 */
    @Test
    void stillRefusesWhenRewriteAlsoClaimsExecution() {
        final int[] composeCalls = {0};
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
                composeCalls[0]++;
                return "已经自动为你完成处置。[1]";
            }
        };
        AgentOrchestrator orchestrator = new AgentOrchestrator(registry(), llm);
        AgentResult result = orchestrator.run("s13", "叶子有褐色轮纹斑", "番茄", null);

        assertEquals(AgentResult.Status.REFUSED, result.getStatus());
        assertEquals(AgentOrchestrator.REFUSAL_ANSWER, result.getAnswer());
        assertEquals(2, composeCalls[0], "只重写一次，不做无休止重试");
    }

    /** 无可靠证据时必须直接拒答，不得白调一次作答步（拒答路径的答案本来就会被丢弃）。 */
    @Test
    void doesNotCallComposeWhenEvidenceIsUnreliable() {
        final int[] composeCalls = {0};
        LlmClient llm = new LlmClient() {
            public String plan(List<Map<String, Object>> history) {
                return "{\"tool\":\"knowledge.search\",\"input\":{\"query\":\"量子计算机\"}}";
            }

            public String compose(List<Map<String, Object>> history) {
                composeCalls[0]++;
                return "无依据";
            }
        };
        AgentOrchestrator orchestrator = new AgentOrchestrator(registry(), llm);
        AgentResult result = orchestrator.run("s8", "量子计算机", "番茄", null);

        assertEquals(AgentResult.Status.REFUSED, result.getStatus());
        assertEquals(0, composeCalls[0], "无可靠证据时不应调用作答步");
    }
}
