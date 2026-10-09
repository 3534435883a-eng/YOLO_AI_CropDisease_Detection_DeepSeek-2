package com.example.Ece.agent.orchestrator;

import com.example.Ece.agent.rag.CitationFormatter;
import com.example.Ece.agent.rag.EmbeddingClient;
import com.example.Ece.agent.rag.KnowledgeChunk;
import com.example.Ece.agent.rag.KnowledgeRetriever;
import com.example.Ece.agent.tool.AgentTool;
import com.example.Ece.agent.tool.AgentToolRegistry;
import com.example.Ece.agent.tool.ToolPermission;
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
            public double[] embed(String text) throws com.example.Ece.agent.rag.EmbeddingUnavailableException {
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
    void boundsToolLoopAndAllowsUncitedGeneralExplanationWhenNoEvidence() {
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
        assertEquals(AgentResult.Status.DONE, result.getStatus());
        assertTrue(result.getCitations().isEmpty(), "未取得依据不能捏造引用");
    }

    /**
     * 域内库缺时允许自然的通用解释；出处由本轮实际引用表示，不强制模板横幅。
     */
    @Test
    void answersFromGeneralKnowledgeWhenDomainMatchedButEvidenceWeak() {
        List<KnowledgeChunk> corpus = new ArrayList<KnowledgeChunk>();
        corpus.add(new KnowledgeChunk("disease", 1L, "番茄", "番茄早疫病",
                KnowledgeChunk.FieldType.SYMPTOM, 0, 0, "番茄叶片出现褐色轮纹斑。", "h1"));
        LlmClient llm = new LlmClient() {
            public String plan(List<Map<String, Object>> history) {
                return history.toString().contains("已提供") || history.size() > 3
                        ? "{\"tool\":\"FINALIZE\",\"input\":{}}"
                        : "{\"tool\":\"knowledge.search\",\"input\":{\"query\":\"褐色的问题要怎么处理呢请问\"}}";
            }

            public String compose(List<Map<String, Object>> history) {
                return "棚内湿度高时应优先通风降湿，并在结果期避免叶面长时间带水。需人工确认。";
            }
        };
        AgentOrchestrator orchestrator = new AgentOrchestrator(registryWith(corpus), llm);
        AgentResult result = orchestrator.run("gk1", "褐色的问题要怎么处理呢请问", "番茄", null);

        assertEquals(AgentResult.Status.DONE, result.getStatus(), "域内库缺时应放开为通用知识作答");
        String reason = null;
        for (AgentStepEvent event : result.getEvents()) {
            if ("final".equals(event.getType())) {
                reason = String.valueOf(event.getPayload().get("reason"));
            }
        }
        assertEquals("GENERAL_KNOWLEDGE", reason, "终态原因码应标明这是通用知识回答");
        assertTrue(result.getAnswer().startsWith("棚内湿度高时应优先通风降湿"),
                "自然解释不应被强制横幅包装，实得：" + result.getAnswer());
        assertTrue(result.getCitations().isEmpty(), "通用知识回答不得带引用编号");
    }

    /**
     * 普通解释不需要固定出处横幅，但不能生成不存在的文献编号。
     */
    @Test
    void acceptsNaturalGeneralAnswerWithoutProvenanceBannerOrInventedCitations() {
        List<KnowledgeChunk> corpus = new ArrayList<KnowledgeChunk>();
        corpus.add(new KnowledgeChunk("disease", 1L, "番茄", "番茄早疫病",
                KnowledgeChunk.FieldType.SYMPTOM, 0, 0, "番茄叶片出现褐色轮纹斑。", "h1"));
        LlmClient llm = new LlmClient() {
            public String plan(List<Map<String, Object>> history) {
                return history.toString().contains("已提供") || history.size() > 3
                        ? "{\"tool\":\"FINALIZE\",\"input\":{}}"
                        : "{\"tool\":\"knowledge.search\",\"input\":{\"query\":\"褐色的问题要怎么处理呢请问\"}}";
            }

            public String compose(List<Map<String, Object>> history) {
                return "棚内湿度高时应优先通风降湿。";   // 自然说明，不加固定横幅
            }
        };
        AgentOrchestrator orchestrator = new AgentOrchestrator(registryWith(corpus), llm);
        AgentResult result = orchestrator.run("gk2", "褐色的问题要怎么处理呢请问", "番茄", null);

        assertEquals(AgentResult.Status.DONE, result.getStatus());
        assertEquals("棚内湿度高时应优先通风降湿。", result.getAnswer());
        assertTrue(result.getCitations().isEmpty());
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
                return "先查看叶片症状并补拍照片。";
            }
        };
        AgentOrchestrator orchestrator = new AgentOrchestrator(registry(), hallucinating);
        AgentResult result = orchestrator.run("s4", "叶片异常", "番茄", null);
        assertTrue(planCalls[0] <= AgentOrchestrator.MAX_STEPS,
                "无效规划也必须受步数上限约束，否则会空转到超时");
        assertEquals(0, result.getSteps());
        assertEquals(AgentResult.Status.DONE, result.getStatus());
        assertTrue(result.getCitations().isEmpty());
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
        assertTrue(result.getAnswer().contains("无法核实"));
        assertFalse(result.getAnswer().contains("已自动开启"));

        String reason = null;
        for (AgentStepEvent event : result.getEvents()) {
            if ("final".equals(event.getType())) {
                reason = String.valueOf(event.getPayload().get("reason"));
            }
        }
        assertEquals("AUTO_EXECUTION_CLAIM", reason, "守门层必须拦下越权的设备执行声明");
    }

    /**
     * 工具的终止信号停止后续工具规划；作答仍可解释具体限制和一般核查步骤。
     */
    @Test
    void stopsImmediatelyWhenToolSignalsTerminal() {
        final int[] planCalls = {0};
        final int[] composeCalls = {0};
        AgentToolRegistry registry = new AgentToolRegistry();
        registry.register(new AgentTool() {
            public String name() {
                return "vision.explain";
            }

            public String description() {
                return "视觉类别解释";
            }

            public ToolPermission permission() {
                return ToolPermission.READ_ONLY;
            }

            public String inputSchemaJson() {
                return "{\"type\":\"object\"}";
            }

            public Map<String, Object> execute(Map<String, Object> input) {
                Map<String, Object> output = new java.util.LinkedHashMap<String, Object>();
                output.put("lowScore", Boolean.TRUE);
                output.put("citations", new java.util.ArrayList<Map<String, Object>>());
                output.put("terminal", Boolean.TRUE);
                output.put("terminalReason", "KNOWLEDGE_INSUFFICIENT");
                output.put("terminalAnswer", "资料库不足：检测类别「潜叶虫」在知识库中没有可核对的对应条目，无法给出诊断或防治建议。");
                return output;
            }
        });
        LlmClient llm = new LlmClient() {
            public String plan(List<Map<String, Object>> history) {
                planCalls[0]++;
                return "{\"tool\":\"vision.explain\",\"input\":{\"classLabel\":\"Leaf_Miner(潜叶虫)\"}}";
            }

            public String compose(List<Map<String, Object>> history) {
                composeCalls[0]++;
                assertTrue(history.toString().contains("没有可核对的对应条目"));
                assertTrue(history.toString().contains("不能用相近病害替代确诊"));
                return "潜叶虫类别暂无可核对资料，先查看叶背虫体与取食痕迹，补拍照片供人工确认。";
            }
        };
        AgentResult result = new AgentOrchestrator(registry, llm).run("s16", "摄像头检出潜叶虫怎么办", "番茄", null);

        assertEquals(AgentResult.Status.DONE, result.getStatus());
        assertEquals(1, planCalls[0], "终止后不得再规划下一步");
        assertEquals(1, composeCalls[0]);
        assertTrue(result.getAnswer().contains("暂无可核对资料"));
        assertTrue(result.getCitations().isEmpty());
        String reason = null;
        for (AgentStepEvent event : result.getEvents()) {
            if ("final".equals(event.getType())) {
                reason = String.valueOf(event.getPayload().get("reason"));
            }
        }
        assertEquals("GENERAL_KNOWLEDGE", reason);
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
        assertTrue(composeSystemPrompt[0].contains("自然中文回答"), "应换成自然作答阶段提示");
        assertTrue(composeSystemPrompt[0].contains("仅引用实际支持它的本轮资料"), "自然回答仍须保留来源要求");
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
     * 有证据但模型没给出回答时，必须如实报失败（ERROR/ANSWER_EMPTY），
     * 不能用拒答文案兜底却报 DONE——界面上那会被显示成"结论"，把失败伪装成成功。
     *
     * <p>同时锁住**文案不与"知识库无依据"混用**：2026-09-26 实测中上游返回一次瞬时 5xx，
     * 用户看到的却是"资料库不足：知识库里没有能支撑这个问题的可靠依据"——证据齐备、检索正常，
     * 只是模型调用失败。把基础设施故障说成知识缺口，会把人引向"去补知识"而不是"重试"。</p>
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

        assertEquals(AgentResult.Status.ERROR, result.getStatus());
        String reason = null;
        for (AgentStepEvent event : result.getEvents()) {
            if ("final".equals(event.getType())) {
                reason = String.valueOf(event.getPayload().get("reason"));
            }
        }
        assertEquals("ANSWER_EMPTY", reason);
        assertEquals(AgentOrchestrator.ANSWER_FAILED_ANSWER, result.getAnswer(),
                "作答失败必须用自己的文案，不得复用'知识库没有依据'的拒答文案");
        assertFalse(result.getAnswer().contains("资料库不足"),
                "作答失败不得说成知识缺口：实测 " + result.getAnswer());
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

    @Test
    void refusesRewriteWithFabricatedCitationNumber() {
        final int[] planCalls = {0};
        final int[] composeCalls = {0};
        LlmClient llm = new LlmClient() {
            public String plan(List<Map<String, Object>> history) {
                planCalls[0]++;
                return planCalls[0] == 1
                        ? "{\"tool\":\"knowledge.search\",\"input\":{\"query\":\"褐色轮纹斑\"}}"
                        : "{\"tool\":\"FINALIZE\",\"input\":{}}";
            }

            public String compose(List<Map<String, Object>> history) {
                composeCalls[0]++;
                return composeCalls[0] == 1 ? "已自动开启设备。[1]" : "建议人工复核。[99]";
            }
        };
        AgentResult result = new AgentOrchestrator(registry(), llm)
                .run("invalid-rewrite-citation", "叶子有褐色轮纹斑", "番茄", null);

        assertEquals(3, composeCalls[0], "越权重写后仍有假引用，应再尝试修正引用；再次假引则拒答");
        assertEquals(AgentResult.Status.REFUSED, result.getStatus());
        assertTrue(result.getCitations().isEmpty());
    }

    @Test
    void carriesCompletedTurnOnlyWithinSameSession() {
        final List<List<Map<String, Object>>> planningHistories = new ArrayList<List<Map<String, Object>>>();
        LlmClient llm = new LlmClient() {
            public String plan(List<Map<String, Object>> history) {
                planningHistories.add(new ArrayList<Map<String, Object>>(history));
                for (Map<String, Object> message : history) {
                    if (String.valueOf(message.get("content")).contains("已获得证据（编号全局一致")) {
                        return "{\"tool\":\"FINALIZE\",\"input\":{}}";
                    }
                }
                return "{\"tool\":\"knowledge.search\",\"input\":{\"query\":\"褐色轮纹斑\"}}";
            }

            public String compose(List<Map<String, Object>> history) {
                return "疑似早疫病，建议复核。[1]";
            }
        };
        AgentOrchestrator orchestrator = new AgentOrchestrator(registry(), llm);
        assertEquals(AgentResult.Status.DONE, orchestrator.run("session-a", "第一轮问题", "番茄", null).getStatus());
        assertEquals(AgentResult.Status.DONE, orchestrator.run("session-a", "追问", "番茄", null).getStatus());
        assertEquals(AgentResult.Status.DONE, orchestrator.run("session-b", "新会话问题", "番茄", null).getStatus());

        assertTrue(planningHistories.get(2).stream().anyMatch(message ->
                "疑似早疫病，建议复核。[1]".equals(message.get("content"))));
        assertFalse(planningHistories.get(4).stream().anyMatch(message ->
                String.valueOf(message.get("content")).contains("第一轮问题")));
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
        assertTrue(result.getAnswer().contains("无法核实"));
        assertFalse(result.getAnswer().contains("已经自动"));
        assertEquals(2, composeCalls[0], "只重写一次，不做无休止重试");
    }

    /**
     * 工具解释进入作答提示，回答可自然说明知识限制而无需套用拒答模板。
     */
    @Test
    void generalAnswerReceivesAndExplainsToolLimitations() {
        AgentToolRegistry registry = new AgentToolRegistry();
        registry.register(new AgentTool() {
            public String name() {
                return "vision.explain";
            }

            public String description() {
                return "视觉类别解释";
            }

            public ToolPermission permission() {
                return ToolPermission.READ_ONLY;
            }

            public String inputSchemaJson() {
                return "{\"type\":\"object\"}";
            }

            public Map<String, Object> execute(Map<String, Object> input) {
                Map<String, Object> output = new java.util.LinkedHashMap<String, Object>();
                output.put("lowScore", Boolean.TRUE);
                output.put("note", "检测类别 Leaf_Miner(潜叶虫) 在知识库中没有可核对的对应条目");
                return output;
            }
        });
        LlmClient llm = new LlmClient() {
            public String plan(List<Map<String, Object>> history) {
                return "{\"tool\":\"vision.explain\",\"input\":{\"classLabel\":\"Leaf_Miner(潜叶虫)\"}}";
            }

            public String compose(List<Map<String, Object>> history) {
                assertTrue(history.toString().contains("没有可核对的对应条目"));
                return "潜叶虫在当前资料中没有可核对的对应条目，请补充叶背和虫体照片。";
            }
        };
        AgentResult result = new AgentOrchestrator(registry, llm).run("s15", "摄像头检出潜叶虫怎么办", "番茄", null);

        assertEquals(AgentResult.Status.DONE, result.getStatus());
        assertTrue(result.getAnswer().contains("没有可核对的对应条目"),
                "一般解释应保留具体限制：" + result.getAnswer());
    }

    /** 无可靠资料仍可解释一般原理，但提示必须禁止编造引用和具体未核实处方。 */
    @Test
    void callsComposeWithExplicitSourceLimitsWhenEvidenceIsUnreliable() {
        final int[] composeCalls = {0};
        LlmClient llm = new LlmClient() {
            public String plan(List<Map<String, Object>> history) {
                return "{\"tool\":\"knowledge.search\",\"input\":{\"query\":\"量子计算机\"}}";
            }

            public String compose(List<Map<String, Object>> history) {
                composeCalls[0]++;
                assertTrue(history.toString().contains("本轮没有取得可引用资料"));
                assertTrue(history.toString().contains("不能编造引用"));
                return "这一问题暂时没有检索到资料，可以先解释一般原理。";
            }
        };
        AgentOrchestrator orchestrator = new AgentOrchestrator(registry(), llm);
        AgentResult result = orchestrator.run("s8", "量子计算机", "番茄", null);

        assertEquals(AgentResult.Status.DONE, result.getStatus());
        assertEquals(1, composeCalls[0]);
        assertTrue(result.getCitations().isEmpty());
    }
    /**
     * **检索降级不能被说成"知识库里没有"**。
     *
     * <p>2026-09-27 实测踩到：聊天页预设示例问题被拒，原因码 `TOOL_REPEAT_LIMIT`，
     * 而步进记录显示两步都 `LOW_SCORE` 且 `degraded=1`、耗时 0~3ms——
     * 向量服务不可达、检索降级为纯 BM25、模型反复重试后触顶。
     * 那句话在知识库里有 7 条依据，服务恢复后重问即正常。
     * 把「服务掉线」说成「知识缺口」会把用户引向补知识，而该做的是重试。</p>
     */
    @Test
    void degradedRetrievalIsReportedAsServiceIssueNotKnowledgeGap() {
        KnowledgeRetriever dead = new KnowledgeRetriever(new EmbeddingClient() {
            public double[] embed(String text) throws com.example.Ece.agent.rag.EmbeddingUnavailableException {
                throw new com.example.Ece.agent.rag.EmbeddingUnavailableException("down");
            }
        });
        dead.rebuild(new ArrayList<KnowledgeChunk>());
        AgentToolRegistry degradedRegistry = new AgentToolRegistry();
        degradedRegistry.register(new KnowledgeSearchTool(dead, new CitationFormatter()));

        LlmClient llm = new LlmClient() {
            public String plan(List<Map<String, Object>> history) {
                return history.toString().contains("未提供可用证据") || history.size() > 3
                        ? "{\"tool\":\"FINALIZE\",\"input\":{}}"
                        : "{\"tool\":\"knowledge.search\",\"input\":{\"query\":\"番茄叶片褐色轮纹\"}}";
            }

            public String compose(List<Map<String, Object>> history) {
                assertTrue(history.toString().contains("检索本轮降级，不等于主题不存在"));
                return "检索能力当前不完整，可先检查病斑形态并补拍叶片照片。";
            }
        };
        AgentOrchestrator orchestrator = new AgentOrchestrator(degradedRegistry, llm);
        AgentResult result = orchestrator.run("dg1", "番茄叶片褐色轮纹", "番茄", null);

        assertEquals(AgentResult.Status.DONE, result.getStatus());
        assertTrue(result.getAnswer().contains("检索能力当前不完整"),
                "降级回答必须说明检索服务限制：" + result.getAnswer());
        assertFalse(result.getAnswer().contains("知识库里没有能支撑这个问题的可靠依据"),
                "降级时不得复用「资料库不足」文案");
        String reason = null;
        for (AgentStepEvent event : result.getEvents()) {
            if ("final".equals(event.getType())) {
                reason = String.valueOf(event.getPayload().get("reason"));
            }
        }
        assertEquals("GENERAL_KNOWLEDGE", reason);
        assertTrue(result.getAnswer().contains("降级"), "检索降级仍应披露");
        assertTrue(result.getCitations().isEmpty());
    }
}
