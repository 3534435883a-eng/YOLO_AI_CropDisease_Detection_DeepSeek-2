package com.example.Ece.agent.orchestrator;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.example.Ece.agent.guard.GuardrailCheck;
import com.example.Ece.agent.guard.GuardrailService;
import com.example.Ece.agent.rag.CitationFormatter;
import com.example.Ece.agent.rag.KnowledgeChunker;
import com.example.Ece.agent.rag.ScoredChunk;
import com.example.Ece.agent.service.AgentChatHistoryService;
import com.example.Ece.agent.support.JsonBlockScanner;
import com.example.Ece.agent.tool.AgentTool;
import com.example.Ece.agent.tool.AgentToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.function.Predicate;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;

/**
 * 智能体编排循环：理解意图 → 选择工具 → 执行 → 观察 → 再决策。
 *
 * 三道闸门保证可控：
 * 1) 规划步数上限 {@link #MAX_STEPS}（含"工具不存在"这类无效规划，防止空转）；
 * 2) 单工具调用次数与"同参数重复调用"拦截；
 * 3) 单步与整轮超时（单步经线程池 {@code Future.get} 强制超时）。
 *
 * 证据可靠性由检索的 lowScore 判定：只有存在关键词证据的命中才算可靠，
 * 否则仅作为一般解释，不拿语义漂移的结果充当引用。引用编号在每轮合并后全局重编号，
 * 保证 LLM 看到的编号与最终展示完全一致。
 */
@Component
public class AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);


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
     * 作答步调用失败时的文案。**必须与 {@link #REFUSAL_ANSWER} 分开**。
     *
     * <p>2026-09-26 实测抓到的错配：上游 DeepSeek 返回一次瞬时 5xx，作答步抛异常，
     * 走 {@code ANSWER_EMPTY} 分支，而该分支当时复用的是拒答文案——于是用户看到
     * "资料库不足：知识库里没有能支撑这个问题的可靠依据"。**证据其实齐备、检索完全正常，
     * 是模型调用失败的**。这条误导两头都坏：用户会以为该问的知识没入库（去补知识），
     * 而真正该做的是重试。相同提问重试一次即正常作答。</p>
     *
     * <p>{@code reason} 里仍如实保留 {@code ANSWER_EMPTY}，便于事后从落库记录区分
     * "拒答"与"失败"；但**给用户看的文案不能再混用**。</p>
     */
    public static final String ANSWER_FAILED_ANSWER =
            "作答失败：本轮已取得的证据仍在，但生成回答的模型调用未成功（可能是服务瞬时故障）。"
                    + "请重试一次；若反复失败请检查模型服务配置。这不是知识库缺少依据。";

    /**
     * **检索降级**时的拒答文案（2026-09-27 新增）。
     *
     * <p>必须与 {@link #REFUSAL_ANSWER} 分开：前者说的是"知识库里没有"，后者说的是
     * "检索能力不完整"。把后者说成前者，会把用户引向**补知识**，而该做的其实是**重试或修服务**。</p>
     *
     * <p>实测踩到：聊天页的**预设示例问题**被拒，原因码 {@code TOOL_REPEAT_LIMIT}——
     * 步进记录显示两步都是 {@code LOW_SCORE} 且 {@code degraded=1}、耗时 0~3ms，
     * 即向量服务（Flask）当时不可达、检索降级为纯 BM25、未达阈值，模型反复重试后触顶。
     * 而那句话在知识库里有 7 条依据，服务恢复后重问即正常。**界面却只显示"已拒答"。**</p>
     */
    public static final String DEGRADED_REFUSAL_ANSWER =
            "检索能力当前不完整：向量检索服务不可达，本轮已降级为纯关键词检索，未能取得可用依据。"
                    + "这**不代表知识库里没有相关内容**——请稍后重试，或确认向量服务（Flask，端口 5000）已启动。";

    /**
     * 作答阶段的系统提示。
     *
     * <p><b>为什么必须单独一套</b>：规划阶段的系统提示写着"每一步只输出一个 JSON"，
     * 模型会一直遵守——实测真实端到端时，作答步返回的是
     * {@code {"tool":"FINALIZE","input":{"answer":"**结论**：…"}}}，用户看到的就是一坨 JSON。
     * 作答阶段必须显式解除 JSON 约束（并继续禁止越权执行声明）。</p>
     */
    static final String ANSWER_SYSTEM_PROMPT =
            "你是农业平台的决策助手。现在用自然中文回答用户，先直接回应关注点，复杂问题才分步展开。"
            +"不要每题套结论/依据/风险模板，不要复述内部工具名，不输出规划JSON。"
            +"可用自身知识解释一般原理；论文结论、具体阈值和处方仅引用实际支持它的本轮资料[编号]。"
            +"没有合适资料时说明具体限制，不能把近似主题硬作依据或编造来源。"
            +"当前状态只按服务端快照，M3是历史观测，scenario是模拟；未测果实/风速/叶面湿润不编造。"
            +"M3 environment与observedHeightCm是历史记录；original/predicted/correctedHeightCm是模型株高，growth及scenario.growth的LAI、干重、果实、阶段为模型或初始化假设，不是M3测量。"
            +"原始土壤VWC未标定，不单凭数值判定缺水或水分充足；historical/estimated原值不能称现场实测。"
            +"设备动作仅按实际工具状态描述，分析中或仅建议不能称已执行。已执行仿真动作必须说仿真，不能说控制实物。"
            +"默认简短而具体，普通状态问题先用3至6句回应；只有用户要求详细对比时才展开表格或全部字段。"
            +"不在正文堆UUID、风险代码或工具内部字段，使用用户熟悉的中文；回答结构按问题决定，不添加不相关的药剂尾注。"
            +"来源由界面展示，正文在被支持的句子处加[编号]，一般不重复罗列网址；除非用户明确需要可复制地址。不能把指南的应急干预阈值说成只有到达它才可干预。";

    private final AgentToolRegistry registry;
    @Autowired(required=false)
    private com.example.Ece.agent.m3.M3LiveService liveService;
    private final LlmClient llmClient;
    private final CitationFormatter citationFormatter = new CitationFormatter();
    private final GuardrailService guardrailService;
    /** 会话历史持久化；为 null 表示不持久化（单元测试默认如此）。 */
    private final AgentChatHistoryService historyService;
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
    public AgentOrchestrator(AgentToolRegistry registry, LlmClient llmClient, GuardrailService guardrailService,
                             AgentChatHistoryService historyService) {
        this.registry = registry;
        this.llmClient = llmClient;
        this.guardrailService = guardrailService;
        this.historyService = historyService;
    }

    /** 便于单元测试：使用默认守门实现，不做历史持久化。 */
    public AgentOrchestrator(AgentToolRegistry registry, LlmClient llmClient) {
        this(registry, llmClient, new GuardrailService(), null);
    }

    /** 便于单元测试：指定守门实现，不做历史持久化。 */
    public AgentOrchestrator(AgentToolRegistry registry, LlmClient llmClient, GuardrailService guardrailService) {
        this(registry, llmClient, guardrailService, null);
    }

    @PreDestroy
    public void shutdown() {
        toolExecutor.shutdownNow();
    }

    @SuppressWarnings("unchecked")
    /**
     * 编排入口。在原有流程之外，把本次的**终态**（回答、状态、拒答原因、引用、步数、工具链）
     * 交给 {@link AgentChatHistoryService} 半永久化。
     *
     * <p>持久化是旁路：{@code record} 内部自身就吞掉全部异常，这里再加一层 try/catch
     * 是有意的双保险——"审计写失败把一次正常对话搞挂"是用户可见的严重故障，
     * 值得用两处防御换掉。任何持久化异常都不会改变这里的返回值。</p>
     *
     * <p><b>覆盖范围</b>：每一次走到 {@code finish} 的终态都会被记录，
     * 包括 DONE、拒答、以及模型调用失败（`plan`/`compose` 抛异常时在内部转成
     * {@code Status.ERROR} + {@code LLM_ERROR}，同样经 {@code finish} 汇聚）。
     * 只有**逃出编排层的编程错误**（如 NPE）不会留下历史行——那属于 bug，
     * 应当由控制器报错与日志暴露，而不是被记成一条"正常的历史"。</p>
     */
    public AgentResult run(String sessionId, String question, String crop, Consumer<AgentStepEvent> sink) {
        return run(sessionId,question,crop,null,null,sink);
    }
    public AgentResult run(String sessionId,String question,String crop,String simulationRunId,Long legacyRunId,Consumer<AgentStepEvent> sink) {
        return run(sessionId,question,crop,simulationRunId,legacyRunId,true,()->false,sink);
    }
    public AgentResult run(String sessionId,String question,String crop,String simulationRunId,Long legacyRunId,
                           boolean allowActions,BooleanSupplier cancelled,Consumer<AgentStepEvent> sink) {
        AgentResult result = runInternal(sessionId, question, crop, simulationRunId,legacyRunId,allowActions,cancelled,sink);
        if (historyService != null) {
            try {
                historyService.record(sessionId, crop, question, result);
            } catch (RuntimeException error) {
                log.warn("会话历史持久化失败，已忽略（回答不受影响）：{}", error.toString());
            }
        }
        return result;
    }

    private AgentResult runInternal(String sessionId, String question, String crop,String simulationRunId,Long legacyRunId,
                                    boolean allowActions,BooleanSupplier cancelled,Consumer<AgentStepEvent> sink) {
        List<AgentStepEvent> events = new ArrayList<AgentStepEvent>();
        List<Map<String, Object>> priorHistory = sessionHistoryStore.snapshot(sessionId);
        List<Map<String, Object>> history = new ArrayList<Map<String, Object>>();
        boolean simulationAvailable=simulationRunId!=null&&!simulationRunId.trim().isEmpty();
        history.add(message("system", systemPrompt(simulationAvailable,simulationAvailable||legacyRunId!=null)));
        // 历史消息放在 system 提示之后，避免旧会话内容覆盖当前编排约束。
        history.addAll(priorHistory);
        history.add(message("user", userPrompt(question, crop)));
        if(!allowActions)history.add(message("user","本轮是重新回答，只重新分析和解释，不执行新的仿真设备动作。"));
        if(simulationRunId!=null&&!simulationRunId.trim().isEmpty()){
            try {
                com.fasterxml.jackson.databind.node.ObjectNode run=liveService.current(simulationRunId,true);
                com.fasterxml.jackson.databind.node.ObjectNode context=compactContext(run.path("current"));
                history.add(message("user","服务端绑定当前M3运行 "+simulationRunId+"；environment及observedHeightCm为历史观测，其他株高及growth为模型计算；scenario为虚拟事件与设备响应。只使用本轮时间的状态："+context));
                Map<String,Object> payload=new LinkedHashMap<>();payload.put("simulationRunId",simulationRunId);payload.put("snapshot",context);
                AgentStepEvent event=new AgentStepEvent("context",0,"simulation.snapshot","已关联当前M3大棚",payload);
                events.add(event);if(sink!=null)sink.accept(event);
            }catch(Exception e){return finish(events,sink,new ArrayList<>(),0,null,AgentResult.Status.ERROR,"SIMULATION_UNAVAILABLE","关联运行不可用，请重开大棚或解除关联后继续提问。");}
        }

        Map<String, Integer> toolCalls = new HashMap<String, Integer>();
        Set<String> seenDigests = new HashSet<String>();
        List<Map<String, Object>> citations = new ArrayList<Map<String, Object>>();
        List<ScoredChunk> evidenceChunks = new ArrayList<ScoredChunk>();
        boolean reliableEvidence = false;
        // 本轮是否**命中过关键词**（农业域内的信号）。用于区分两种"没有可靠证据"：
        // 关键词零命中 = 问题与农业语料毫无交集（离题）；有命中而低分 = 域内但库缺依据。
        boolean sawKeywordHits = false;
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
            if(stopped(cancelled))return cancelled(events,sink,executed);
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
            if(stopped(cancelled))return cancelled(events,sink,executed);
            if (plan == null) {
                blockReason = "PLAN_UNPARSEABLE";
                break;
            }
            String toolName = plan.getString("tool");
            if (toolName == null || "FINALIZE".equalsIgnoreCase(toolName)) {
                break;
            }
            AgentTool tool = registry.find(toolName);
            if ((!simulationAvailable && toolName.startsWith("simulation."))
                    || (!simulationAvailable && legacyRunId==null && "platform.greenhouseState".equals(toolName))) {
                history.add(message("user","本轮是独立问答，没有关联大棚；请回应用户问题，不读取或操作模拟大棚。"));
                continue;
            }
            if (tool == null) {
                history.add(message("user", "工具 " + toolName + " 不存在，请从目录中选择，或直接 FINALIZE。"));
                continue;
            }
            Map<String, Object> input = toMap(plan.getJSONObject("input"));
            if(!allowActions&&"simulation.decide".equals(toolName)){toolName="simulation.snapshot";tool=registry.find(toolName);input.clear();}
            if(toolName.startsWith("simulation.")){
                input.put("simulationRunId",simulationRunId);input.put("question",question);
                input.put("applyAuthorized",allowActions&&isExecutionRequest(question));
            }else if("platform.greenhouseState".equals(toolName)){
                if(simulationRunId!=null&&!simulationRunId.trim().isEmpty()){
                    tool=registry.find("simulation.snapshot");toolName="simulation.snapshot";input.clear();input.put("simulationRunId",simulationRunId);
                }else if(legacyRunId!=null)input.put("runId",legacyRunId);
            }
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
            if(stopped(cancelled))return cancelled(events,sink,executed);
            Map<String, Object> output = executeWithTimeout(tool, input, cancelled);
            long durationMs = System.currentTimeMillis() - stepStartedAt;
            executed++;
            Map<String,Object> useful=new LinkedHashMap<>(output);useful.remove("items");useful.remove("citations");
            if(useful.get("snapshot") instanceof com.fasterxml.jackson.databind.JsonNode)useful.put("snapshot",compactContext((com.fasterxml.jackson.databind.JsonNode)useful.get("snapshot")));
            if(useful.get("scenario") instanceof com.fasterxml.jackson.databind.node.ObjectNode){
                com.fasterxml.jackson.databind.node.ObjectNode slim=((com.fasterxml.jackson.databind.node.ObjectNode)useful.get("scenario")).deepCopy();
                slim.remove("trends");slim.remove("timeline");slim.remove("shadowRisk");slim.remove("withoutIntervention");useful.put("scenario",slim);
            }
            String toolObservation=new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(useful).toString();
            history.add(message("user","工具观察结果："+(toolObservation.length()>11000?toolObservation.substring(0,10900)+"（观察摘要已截断；未显示的字段不可推测）":toolObservation)));

            List<Map<String, Object>> stepCitations = new ArrayList<Map<String, Object>>();
            Object rawCitations = output.get("citations");
            boolean lowScore = Boolean.TRUE.equals(output.get("lowScore"));
            Object rawHits = output.get("bm25HitCount");
            if (rawHits instanceof Number && ((Number) rawHits).intValue() > 0) {
                sawKeywordHits = true;
            }
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

            // 工具调用审计轨迹。此前 agent_step_trace 表与 AgentStepTraceRepository 都存在但零调用方，
            // 文档却宣称有审计——2026-09-26 端到端验证时发现。走与历史写入同一个旁路（永不抛异常）。
            if (historyService != null) {
                Long traceRunId = null;
                Object rawRunId = output.get("runId");
                if (rawRunId instanceof Number) {
                    traceRunId = Long.valueOf(((Number) rawRunId).longValue());
                }
                String traceStatus = output.get("error") != null ? "ERROR" : (lowScore ? "LOW_SCORE" : "OK");
                try {
                    historyService.recordStep(sessionId, traceRunId, executed, toolName, digest, output,
                            durationMs, Boolean.TRUE.equals(output.get("degraded")), traceStatus);
                } catch (RuntimeException error) {
                    log.warn("工具调用轨迹记录失败，已忽略：{}", error.toString());
                }
            }

            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("citations", citations);
            payload.put("stepCitations", stepCitations);
            payload.put("degraded", Boolean.valueOf(Boolean.TRUE.equals(output.get("degraded"))));
            payload.put("degradedReason", output.get("degradedReason"));
            payload.put("lowScore", Boolean.valueOf(lowScore));
            // 关键词命中数**必须透传到流里**（2026-09-27 补）：它是"这次为什么走了通用知识
            // 而不是拒答"的唯一判据（0 命中=离题→拒答；>0 低分=域内库缺→通用知识作答）。
            // 不透传的话，接口层面只看到一个 DONE，排查得翻后端日志。
            Object hitsInPayload = output.get("bm25HitCount");
            if (hitsInPayload != null) {
                payload.put("bm25HitCount", hitsInPayload);
            }
            payload.put("durationMs", Long.valueOf(durationMs));
            payload.put("error", output.get("error"));
            // 工具入参一并下发：前端可展示"用什么参数调的"，排查时也不必靠猜。
            payload.put("input", input);
            payload.put("note", output.get("note"));
            payload.put("mapping", output.get("mapping"));
            if(output.get("scenario")!=null)payload.put("simulation",output.get("scenario"));
            if(output.get("snapshot") instanceof com.fasterxml.jackson.databind.JsonNode)
                payload.put("simulation",((com.fasterxml.jackson.databind.JsonNode)output.get("snapshot")).path("scenario"));

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
                history.add(message("user","该识别/检索有明确限制："+terminalAnswer+"。不能用相近病害替代确诊；可以说明限制并给一般检查建议。"));
                blockReason=terminalReason==null?"TOOL_TERMINAL":String.valueOf(terminalReason);break;
            }
        }

        if (!reliableEvidence) {
            history.add(message("user","本轮没有取得可引用资料。仍可用自身知识给一般解释与可用建议；"
                    +"具体未核实的阈值、处方或论文结论只说明限制，不能编造引用，也不要使用固定声明横幅。"));
        }
        if(degradedSeen)history.add(message("user","检索本轮降级，不等于主题不存在；仅对需资料核实的部分说明限制。"));
        if(stopped(cancelled))return cancelled(events,sink,executed);
        String answer;
        try {answer=unwrapAnswer(llmClient.compose(composeHistory(history)));}
        catch(RuntimeException error){return finish(events,sink,new ArrayList<>(),executed,null,
                AgentResult.Status.ERROR,"LLM_ERROR",ANSWER_FAILED_ANSWER);}
        if(answer==null||answer.trim().isEmpty())return finish(events,sink,new ArrayList<>(),executed,null,
                AgentResult.Status.ERROR,"ANSWER_EMPTY",ANSWER_FAILED_ANSWER);
        if(stopped(cancelled))return cancelled(events,sink,executed);
        boolean executedSimulation=hasSimulationExecution(events);
        GuardrailCheck guardrail=guardrailService.check(answer,evidenceChunks,degradedSeen,executedSimulation);
        if(!guardrail.isAllowed()){
            List<Map<String,Object>> retry=composeHistory(history);
            retry.add(message("user","上次回答含无法核实的执行声明或药剂处方。请保留有用的解释，去除未核实的具体处方/执行声明，"
                    +"执行状态仅按实际工具结果描述，仿真动作必须明确说仿真。"));
            try {answer=unwrapAnswer(llmClient.compose(retry));guardrail=guardrailService.check(answer,evidenceChunks,degradedSeen,executedSimulation);}
            catch(RuntimeException ignored){}
        }
        if(!guardrail.isAllowed())return finish(events,sink,new ArrayList<>(),executed,null,
                AgentResult.Status.REFUSED,guardrail.getReason(),"这部分处方或执行结果无法核实。可以继续提供环境分析，或先补充登记标签、实际执行记录。");
        answer=guardrail.getRewrittenAnswer();
        // Reject invented reference numbers before publication; allow an uncited general explanation.
        java.util.regex.Matcher verify=java.util.regex.Pattern.compile("\\[(\\d+)\\]").matcher(answer);
        boolean invalid=false;
        while(verify.find()){try{int n=Integer.parseInt(verify.group(1));if(n<1||n>citations.size())invalid=true;}catch(NumberFormatException e){invalid=true;}}
        if(invalid){
            List<Map<String,Object>> retry=composeHistory(history);
            retry.add(message("user","请修正引用：只有本轮证据编号1至"+citations.size()+"可使用；没有支持的结论改为条件性一般说明，不编造来源。"));
            try{answer=unwrapAnswer(llmClient.compose(retry));}catch(RuntimeException e){return finish(events,sink,new ArrayList<>(),executed,null,AgentResult.Status.ERROR,"LLM_ERROR",ANSWER_FAILED_ANSWER);}
            GuardrailCheck checked=guardrailService.check(answer,evidenceChunks,degradedSeen,executedSimulation);
            if(!checked.isAllowed()||answer==null)return finish(events,sink,new ArrayList<>(),executed,null,AgentResult.Status.REFUSED,"CITATION_INVALID","本轮引用无法核对，请重试或查看已取得的资料。");
            answer=checked.getRewrittenAnswer();
            java.util.regex.Matcher again=java.util.regex.Pattern.compile("\\[(\\d+)\\]").matcher(answer);
            while(again.find()){try{int n=Integer.parseInt(again.group(1));if(n<1||n>citations.size())return finish(events,sink,new ArrayList<>(),executed,null,AgentResult.Status.REFUSED,"CITATION_INVALID","模型返回了无法核对的引用编号，请重试。");}catch(NumberFormatException e){return finish(events,sink,new ArrayList<>(),executed,null,AgentResult.Status.REFUSED,"CITATION_INVALID","引用编号无效，请重试。");}}
        }
        Map<Integer,Integer> used=new LinkedHashMap<>();
        java.util.regex.Matcher refs=java.util.regex.Pattern.compile("\\[(\\d+)\\]").matcher(answer);
        StringBuffer body=new StringBuffer();List<Map<String,Object>> selected=new ArrayList<>();
        while(refs.find()){
            int old=Integer.parseInt(refs.group(1));
            if(!used.containsKey(old)){int no=used.size()+1;used.put(old,no);Map<String,Object> c=new LinkedHashMap<>(citations.get(old-1));c.put("index",no);selected.add(c);}
            refs.appendReplacement(body,"["+used.get(old)+"]");
        }
        refs.appendTail(body);answer=body.toString();selected=citationFormatter.renumber(selected);
        sessionHistoryStore.append(sessionId,question,answer);
        return finish(events,sink,selected,executed,answer,AgentResult.Status.DONE,selected.isEmpty()?"GENERAL_KNOWLEDGE":null,null);
    }

    private com.fasterxml.jackson.databind.node.ObjectNode compactContext(com.fasterxml.jackson.databind.JsonNode frame){
        com.fasterxml.jackson.databind.node.ObjectNode copy=(com.fasterxml.jackson.databind.node.ObjectNode)frame.deepCopy();
        copy.remove("plants");copy.remove("updates");
        if(copy.path("scenario").isObject()){
            com.fasterxml.jackson.databind.node.ObjectNode scenario=(com.fasterxml.jackson.databind.node.ObjectNode)copy.path("scenario");
            scenario.remove("trends");scenario.remove("timeline");
            scenario.remove("shadowRisk");scenario.remove("withoutIntervention");scenario.remove("assumptions");
            scenario.remove("parameters");
            if(scenario.path("decision").path("plan").isObject())((com.fasterxml.jackson.databind.node.ObjectNode)scenario.path("decision").path("plan")).remove("references");
        }
        return copy;
    }
    private boolean isExecutionRequest(String question){
        if(question==null||question.matches("(?s).*?(不要|不执行|先别|别执行|只建议|只分析).*"))return false;
        return java.util.regex.Pattern.compile("(请|帮我|现在|直接|立即|给我).{0,16}(执行|开启|打开|关闭|启动|处理|解决)|^(执行|开启|打开|关闭|启动|处理)|^把.{0,12}(开|关|停)").matcher(question).find();
    }
    private boolean hasSimulationExecution(List<AgentStepEvent> events){
        for(AgentStepEvent e:events){
            Object data=e.getPayload().get("simulation");
            if(data instanceof com.fasterxml.jackson.databind.JsonNode && ((com.fasterxml.jackson.databind.JsonNode)data).has("decision")
                    && ((com.fasterxml.jackson.databind.JsonNode)data).path("decision").has("executedAt"))return true;
            Object snapshot=e.getPayload().get("snapshot");
            if(snapshot instanceof com.fasterxml.jackson.databind.JsonNode && ((com.fasterxml.jackson.databind.JsonNode)snapshot).path("scenario").path("decision").has("executedAt"))return true;
        }
        return false;
    }

    private boolean stopped(BooleanSupplier cancellation){return Thread.currentThread().isInterrupted()||cancellation.getAsBoolean();}
    private AgentResult cancelled(List<AgentStepEvent> events,Consumer<AgentStepEvent> sink,int executed){
        return finish(events,sink,new ArrayList<>(),executed,null,AgentResult.Status.ERROR,"CANCELLED","回答已停止。已提交的仿真方案请查看大棚状态。");
    }
    private Map<String, Object> executeWithTimeout(final AgentTool tool, final Map<String, Object> input,final BooleanSupplier cancelled) {
        Future<Map<String, Object>> future = toolExecutor.submit(new Callable<Map<String, Object>>() {
            public Map<String, Object> call() throws Exception {
                if(stopped(cancelled))return errorOutput("CANCELLED","回答已停止");
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
            future.cancel(true);
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
        // DONE 一律不带 reason。blockReason 会在"重复工具调用"等情况下被置上，
        // 但循环随后仍可能凭已有证据成功作答——若原样透传，成功回答就会挂着
        // "DUPLICATE_TOOL_CALL" 这类内部原因（2026-09-26 真实会话落库后发现：
        // agent_chat_history 出现 status=DONE 而 refusal_reason=DUPLICATE_TOOL_CALL）。
        // reason 只对非 DONE 终态有意义——**唯一例外是 GENERAL_KNOWLEDGE**：
        // 它不是内部故障码，而是**回答性质**的标记（"这段是模型自己的通用知识，不来自知识库"）。
        // 若也按 DONE 一律清空，客户端无法标注、审计记录里也留不下痕迹，
        // 那"未引用知识库"就成了无痕的——而它恰恰是放开提示词后最需要被看见的一件事。
        boolean generalKnowledge = status == AgentResult.Status.DONE
                && "GENERAL_KNOWLEDGE".equals(reason);
        payload.put("reason", status == AgentResult.Status.DONE
                ? (generalKnowledge ? reason : null) : reason);
        if (generalKnowledge) {
            payload.put("generalKnowledge", Boolean.TRUE);
        }
        for(AgentStepEvent e:events){
            if(e.getPayload().containsKey("snapshot"))payload.put("simulationContext",e.getPayload().get("snapshot"));
            if(e.getPayload().containsKey("simulation"))payload.put("simulation",e.getPayload().get("simulation"));
        }
        payload.put("answerBasis",kept.isEmpty()?"MODEL_EXPLANATION":"CITED_SOURCES");
        payload.put("citations",kept);
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
     * 这是最容易出现的"智能体一步不动"故障。扫描逻辑已抽到 {@link JsonBlockScanner}，
     * 与农事规划推演链路共用同一份实现。</p>
     *
     * <p>只认带 {@code tool} 字段的对象：模型在给正式动作前先输出一段示例 JSON 是常态。</p>
     */
    private JSONObject parsePlan(String raw) {
        return JsonBlockScanner.firstObject(raw, new Predicate<JSONObject>() {
            public boolean test(JSONObject object) {
                return object.containsKey("tool");
            }
        });
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
            int end = JsonBlockScanner.matchingBrace(text, start);
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

    /**
     * 步骤时间线上的一句话说明。
     *
     * <p>措辞**优先由工具自报**（{@code stepSummary}）。此前这里对所有工具都写"N 条命中"，
     * 那是检索工具的语义——状态类工具（如 {@code platform.greenhouseState}）输出
     * "命中 0 条"是误导：它根本不返回命中条目，0 不代表失败。
     * 工具不报时退化为中性措辞，不再假装每个工具都是检索工具。</p>
     */
    private String summarize(String toolName, int citationCount, long durationMs, Map<String, Object> output) {
        if (output.get("error") != null) {
            return toolName + " 执行失败：" + output.get("error");
        }
        Object reported = output.get("stepSummary");
        StringBuilder builder = new StringBuilder();
        if (reported != null && !String.valueOf(reported).trim().isEmpty()) {
            builder.append(String.valueOf(reported).trim());
        } else {
            builder.append(toolName).append(" 完成");
        }
        if (citationCount > 0) {
            builder.append("，引用 ").append(citationCount).append(" 条");
        }
        builder.append("，耗时 ").append(durationMs).append("ms")
                .append(Boolean.TRUE.equals(output.get("degraded")) ? "（降级：仅关键词检索）" : "")
                .append(Boolean.TRUE.equals(output.get("lowScore")) ? "（相关性不足）" : "");
        return builder.toString();
    }

    private String systemPrompt(boolean simulationAvailable,boolean greenhouseStateAvailable) {
        return "你是农业平台的决策助手，围绕用户选择的作物和问题回答。可用工具目录：" + registry.catalogJson(simulationAvailable,greenhouseStateAvailable)
                + "。每一步只输出一个 JSON：{\"tool\":\"工具名\",\"input\":{...}}；"
                + "一般交流和概念解释可直接FINALIZE；具体数值/文献/处方再检索；当前大棚先使用服务端上下文或simulation.snapshot。"
                + "只有用户要求制定或执行仿真方案才选simulation.decide；已提交不等于执行。"
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
