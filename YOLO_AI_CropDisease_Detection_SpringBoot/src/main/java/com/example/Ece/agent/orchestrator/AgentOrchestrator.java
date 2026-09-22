package com.example.Ece.agent.orchestrator;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.example.Ece.agent.guard.GuardrailCheck;
import com.example.Ece.agent.guard.GuardrailService;
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
    public static final String REFUSAL_ANSWER =
            "没有检索到可靠依据，暂不给出结论；建议补充叶片照片或联系当地农技人员核实。";

    private final AgentToolRegistry registry;
    private final LlmClient llmClient;
    private final CitationFormatter citationFormatter = new CitationFormatter();
    private final GuardrailService guardrailService;
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
        List<Map<String, Object>> history = new ArrayList<Map<String, Object>>();
        history.add(message("system", systemPrompt()));
        history.add(message("user", userPrompt(question, crop)));

        Map<String, Integer> toolCalls = new HashMap<String, Integer>();
        Set<String> seenDigests = new HashSet<String>();
        List<Map<String, Object>> citations = new ArrayList<Map<String, Object>>();
        List<ScoredChunk> evidenceChunks = new ArrayList<ScoredChunk>();
        boolean reliableEvidence = false;
        boolean degradedSeen = false;
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

            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("citations", citations);
            payload.put("stepCitations", stepCitations);
            payload.put("degraded", Boolean.valueOf(Boolean.TRUE.equals(output.get("degraded"))));
            payload.put("degradedReason", output.get("degradedReason"));
            payload.put("lowScore", Boolean.valueOf(lowScore));
            payload.put("durationMs", Long.valueOf(durationMs));
            payload.put("error", output.get("error"));

            AgentStepEvent stepEvent = new AgentStepEvent("step", executed, toolName,
                    summarize(toolName, stepCitations.size(), durationMs, output), payload);
            events.add(stepEvent);
            if (sink != null) {
                sink.accept(stepEvent);
            }
        }

        String answer = null;
        try {
            answer = llmClient.compose(history);
        } catch (RuntimeException error) {
            answer = null;
        }
        if (!reliableEvidence) {
            return finish(events, sink, new ArrayList<Map<String, Object>>(), executed, null,
                    AgentResult.Status.REFUSED, blockReason == null ? "NO_RELIABLE_EVIDENCE" : blockReason,
                    REFUSAL_ANSWER);
        }
        if (answer == null || answer.trim().isEmpty()) {
            answer = REFUSAL_ANSWER;
        }
        GuardrailCheck guardrail = guardrailService.check(answer, evidenceChunks, degradedSeen);
        if (!guardrail.isAllowed()) {
            return finish(events, sink, new ArrayList<Map<String, Object>>(), executed, null,
                    AgentResult.Status.REFUSED, guardrail.getReason(), REFUSAL_ANSWER);
        }
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

    private JSONObject parsePlan(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            JSONObject object = JSON.parseObject(raw.trim());
            return object != null && object.containsKey("tool") ? object : null;
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
                + "禁止输出 JSON 以外的内容。不得编造工具名。";
    }

    private String userPrompt(String question, String crop) {
        return "用户问题：" + (question == null ? "" : question)
                + (crop == null || crop.trim().isEmpty() ? "" : "；作物：" + crop);
    }
}
