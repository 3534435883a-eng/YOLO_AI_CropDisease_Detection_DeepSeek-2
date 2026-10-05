package com.example.Ece.agent.tool;

import com.example.Ece.agent.dto.AgentRunResponse;
import com.example.Ece.agent.engine.TomatoDecisionPolicy;
import com.example.Ece.agent.model.AgentDeviceCodes;
import com.example.Ece.agent.model.DecisionPlan;
import com.example.Ece.agent.model.DeviceCommand;
import com.example.Ece.agent.model.SimulationState;
import com.example.Ece.agent.rag.CitationFormatter;
import com.example.Ece.agent.rag.KnowledgeRetriever;
import com.example.Ece.agent.rag.RetrievalResult;
import com.example.Ece.agent.rag.ScoredChunk;
import com.example.Ece.agent.service.AgentRunService;
import com.example.Ece.agent.service.Prescription;
import com.example.Ece.agent.service.PrescriptionService;
import com.example.Ece.agent.service.WaterFertilizerPrescription;
import com.example.Ece.agent.service.WaterFertilizerPrescriptionService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 处方拟制工具：让智能体"能给出方案"。
 *
 * <p>平台早有 {@link PrescriptionService}（结论 / 动作 / 注意事项 / {@code requiresManualConfirm}），
 * 数据结构天生就是为"智能体拟方案、人来确认"设计的，只是从来没接给智能体。</p>
 *
 * <p><b>权限为 DRAFT，不是 WRITE。</b>只产出草案，不操作设备、不写数据库、不改运行状态。</p>
 *
 * <p><b>引用指向什么（关键）</b>：引用的是**草案的依据**——即"规则层基于第 N 步快照给出了这些建议"
 * 这一可核对的事实，而**不是**把草案本身当成事实来引用。
 * 这样"每个结论都能追到编号来源"这条纪律仍然成立：模型可以引用它来陈述
 * "规则层对该状态建议通风灌溉"，但不能把建议说成已经发生的事。
 * 输出固定带 {@code executed=false} 与"待人工确认"，{@code GuardrailService} 另有一层越权拦截。</p>
 *
 * <p><b>依据来源必须讲清</b>：这里用 {@link TomatoDecisionPolicy} 对**当前状态**独立重算一次规则层决策，
 * 因此它是"规则层此刻会怎么做"，与该运行**实际已执行**的决策可能不同
 * （人工接管、设备故障、资源不足都会改变实际动作）。</p>
 */
@Component
public class PrescriptionDraftTool implements AgentTool {

    public static final String NAME = "prescription.draft";

    private static final int EVIDENCE_TOP_N = 5;
    private static final String TITLE = "规则层处方推演";

    /**
     * 未指定 topic 时的默认检索主题。
     *
     * <p>用一组水肥相关的常用词而不是单个词：检索是 BM25 + 向量的融合，主题词太少时
     * 容易只命中"水"或只命中"肥"一头。措辞刻意贴近标准条文的写法（"灌溉制度""施肥制度"），
     * 因为知识库里是标准原文摘要。</p>
     */
    private static final String DEFAULT_TOPIC = "番茄 水肥管理 灌溉制度 施肥制度 追肥 灌水量";

    private final AgentRunService runService;
    private final PrescriptionService prescriptionService;
    private final TomatoDecisionPolicy decisionPolicy;
    private final KnowledgeRetriever retriever;
    private final CitationFormatter citationFormatter;
    private final PlatformSnapshotEvidence evidence;
    private final WaterFertilizerPrescriptionService waterFertilizerService;
    private final ParameterEvidence parameterEvidence;

    public PrescriptionDraftTool(AgentRunService runService, PrescriptionService prescriptionService,
                                 TomatoDecisionPolicy decisionPolicy, KnowledgeRetriever retriever,
                                 CitationFormatter citationFormatter, PlatformSnapshotEvidence evidence,
                                 WaterFertilizerPrescriptionService waterFertilizerService,
                                 ParameterEvidence parameterEvidence) {
        this.runService = runService;
        this.prescriptionService = prescriptionService;
        this.decisionPolicy = decisionPolicy;
        this.retriever = retriever;
        this.citationFormatter = citationFormatter;
        this.evidence = evidence;
        this.waterFertilizerService = waterFertilizerService;
        this.parameterEvidence = parameterEvidence;
    }

    public String name() {
        return NAME;
    }

    public String description() {
        return "依据当前温室状态与确定性规则层，拟一份**待人工确认**的处置处方草案："
                + "含结论、建议动作、注意事项与风险等级，可附带知识库依据；"
                + "并给出**带量的水肥处方**——灌溉触发点、单次滴水量、全季追肥总量与施用节点，"
                + "每条标明出处（部分条目有论文出处，部分未登记出处，输出里会区分）。"
                + "用于回答「那我该怎么处理」「现在要不要通风灌溉」「浇多少水、追什么肥、用多少量」这类问题。"
                + "处方只是草案，本工具不会执行任何设备操作。";
    }

    public ToolPermission permission() {
        return ToolPermission.DRAFT;
    }

    public String inputSchemaJson() {
        return "{\"type\":\"object\",\"properties\":{"
                + "\"runId\":{\"type\":\"integer\",\"description\":\"模拟运行编号，可选；缺省用当前活动运行\"},"
                + "\"topic\":{\"type\":\"string\",\"description\":\"要附带的依据主题，如病名或症状描述，可选\"},"
                + "\"crop\":{\"type\":\"string\",\"description\":\"作物名称，可选\"}},"
                + "\"required\":[]}";
    }

    public Map<String, Object> execute(Map<String, Object> input) throws ToolException {
        Long runId = readRunId(input);
        if (runId == null) {
            AgentRunResponse active = runService.getActiveRun();
            if (active == null || active.getId() == null) {
                return unavailable("当前没有进行中的温室模拟运行，无法拟制处方。"
                        + "请先在智能体指挥中心创建并启动一次模拟运行。");
            }
            runId = active.getId();
        }

        SimulationState state = runService.currentState(runId);
        if (state == null) {
            return unavailable("运行 " + runId + " 没有可用状态（既无快照也无基线），无法拟制处方。");
        }
        int stepNo = currentStep(runId);
        AgentRunResponse sourceRun = runService.getSummary(runId).getRun();
        int tickMinutes = sourceRun.getTickMinutes() == null ? AgentRunService.DEFAULT_TICK_MINUTES : sourceRun.getTickMinutes();
        DecisionPlan plan = decisionPolicy.decide(state, tickMinutes);
        RetrievalResult retrieval = retrieveEvidence(input);
        List<ScoredChunk> kbEvidence = retrieval == null
                ? new ArrayList<ScoredChunk>() : retrieval.getItems();
        Prescription prescription = prescriptionService.draft(state, plan, kbEvidence);
        WaterFertilizerPrescription waterFertilizer = waterFertilizerService.draft(state, tickMinutes);

        // 引用依据：① 规则层基于该快照的推演；② 水肥处方里有出处的参数。
        // 两者都**不是**把草案当事实引用——① 引的是"规则层此刻会这么做"这一可核对事实，
        // ② 引的是参数取值本身（论文/标准），与其在别处被引用时的含义一致。
        List<ScoredChunk> items = new ArrayList<ScoredChunk>();
        items.addAll(evidence.items(runId, stepNo, "番茄", TITLE,
                basisText(runId, stepNo, state, plan, prescription)));
        items.addAll(parameterEvidence.items("番茄", waterFertilizer));
        // 知识库依据也进引用编号——这一条是实测补的：规划步会因为本工具"看起来已经能答水肥问题"
        // 而不再调用 knowledge.search，标准里的逐生育期用量与肥料品种于是永远进不了回答。
        // 判据沿用检索层的 lowScore，与 knowledge.search 完全一致：相关性不足的片段不占编号，
        // 否则等于开了个绕过相关性的后门。
        boolean kbLowScore = retrieval == null || retriever.isLowScore(retrieval);
        if (retrieval != null && !kbLowScore) {
            items.addAll(kbEvidence);
        }

        Map<String, Object> output = new LinkedHashMap<String, Object>();
        output.put("available", Boolean.TRUE);
        output.put("runId", runId);
        output.put("tickMinutes", tickMinutes);
        output.put("profile", sourceRun.getProfile());
        output.put("conclusion", prescription.getConclusion());
        output.put("actions", prescription.getActions());
        output.put("cautions", prescription.getCautions());
        output.put("requiresManualConfirm", Boolean.valueOf(prescription.isRequiresManualConfirm()));
        output.put("riskLevel", state.getRiskLevel());
        output.put("proposedDeviceStates", proposedDeviceStates(plan));
        output.put("waterFertilizer", waterFertilizerView(waterFertilizer));
        output.put("items", items);
        output.put("citations", evidence.citations(items));
        output.put("lowScore", Boolean.FALSE);
        output.put("kbEvidence", citationFormatter.toCitations(kbEvidence));
        output.put("kbEvidenceDegraded", Boolean.valueOf(retrieval != null && retrieval.isDegraded()));
        output.put("stepSummary", NAME + " 拟出 " + prescription.getActions().size() + " 条建议动作（草案）");
        output.put("source", "SIMULATED");
        output.put("executed", Boolean.FALSE);
        output.put("note", "本处方是**待人工确认的草案**，智能体不会执行任何设备操作，"
                + "也不会改变任何运行状态。处方由规则层对当前状态独立重算得出，"
                + "与该运行实际已执行的决策可能不同（人工接管、设备故障、资源不足都会改变实际动作）；"
                + "单步耗量按 " + tickMinutes + " 分钟折算，为未校准模拟定额；M3 原始农事数据尚未导入。"
                + "涉及药剂时须遵循当地登记与用药规范并人工确认。"
                // 草案要点必须走 note：证据块里每条来源只截前 160 字，动作清单放不进引用正文。
                // note 是本编排层唯一完整传给模型的工具输出通道（见 AgentOrchestrator 的 history.add 分支）。
                + "（草案要点：规则层对第 " + stepNo + " 步状态独立重算，建议动作 "
                + compactActions(plan) + "。）"
                // 水肥处方**必须走 note 走不到别处**：证据块每条只截前 160 字，放不下条目清单；
                // 而 note 是编排层唯一完整传给模型的工具输出通道。数值只在这里出现一次，
                // 就是为了让模型不必从 actions 的人读句子里抠数字（抠错的风险实测存在）。
                + waterFertilizerNote(waterFertilizer));
        return output;
    }

    /**
     * 水肥处方要点（进 note）。
     *
     * <p>带**单位与出处状态**一并给：只给数字，模型复述时容易把 171 kg/hm² 写成"171 公斤"，
     * 或把无出处的 60 L 当成有依据的量来陈述。未建模项也一并带出，
     * 否则模型看不到"生育期没接入"这件事，会把追肥制度说成"现在就该施"。</p>
     */
    private String waterFertilizerNote(WaterFertilizerPrescription waterFertilizer) {
        if (waterFertilizer == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("（水肥处方草案：").append(waterFertilizer.getSummary()).append(' ');
        builder.append(waterFertilizer.compact()).append('。');
        if (!waterFertilizer.getUnmodelled().isEmpty()) {
            builder.append("本处方未覆盖：");
            for (int i = 0; i < waterFertilizer.getUnmodelled().size(); i++) {
                if (i > 0) {
                    builder.append("；");
                }
                builder.append(waterFertilizer.getUnmodelled().get(i));
            }
            builder.append('。');
        }
        return builder.append("）").toString();
    }

    /** 结构化下发给前端/模型的水肥处方视图。字段名与其它工具保持一致（items/cautions）。 */
    private Map<String, Object> waterFertilizerView(WaterFertilizerPrescription waterFertilizer) {
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        if (waterFertilizer == null) {
            return view;
        }
        view.put("summary", waterFertilizer.getSummary());
        view.put("irrigationDue", Boolean.valueOf(waterFertilizer.isIrrigationDue()));
        view.put("soilMoisturePct", Double.valueOf(waterFertilizer.getSoilMoisturePct()));
        view.put("triggerPct", Double.valueOf(waterFertilizer.getTriggerPct()));
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (WaterFertilizerPrescription.Item item : waterFertilizer.getItems()) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("key", item.getKey());
            row.put("label", item.getLabel());
            row.put("value", item.getValue());
            row.put("unit", item.getUnit());
            row.put("parameterCode", item.getParameterCode());
            row.put("sourceName", item.getSourceName());
            row.put("sourceUrl", item.getSourceUrl());
            row.put("citable", Boolean.valueOf(item.isCitable()));
            rows.add(row);
        }
        view.put("items", rows);
        view.put("cautions", waterFertilizer.getCautions());
        view.put("unmodelled", waterFertilizer.getUnmodelled());
        return view;
    }

    /** 动作清单的紧凑写法，供 note 使用：只列设备名，不提规则码与耗量。 */
    private String compactActions(DecisionPlan plan) {
        if (plan == null || plan.getCommands() == null) {
            return "无";
        }
        StringBuilder builder = new StringBuilder();
        for (DeviceCommand command : plan.getCommands()) {
            if (!command.isTargetOn()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append('、');
            }
            builder.append(AgentDeviceCodes.label(command.getDeviceCode()));
        }
        return builder.length() == 0 ? "当前无需调整设备，保持现有管理" : builder.toString();
    }

    /**
     * 依据正文。
     *
     * <p><b>字数预算是硬约束</b>：模型在证据块里只看得到 {@code SNIPPET_LIMIT = 160} 字的 snippet，
     * 初版把"这是草案"的限定语写在末尾被整段截掉（端到端测试抓到）。
     * 因此限定语放最前，其后只放状态与动作数，详细动作走 {@code note} 通道。</p>
     */
    private String basisText(long runId, int stepNo, SimulationState state, DecisionPlan plan,
                             Prescription prescription) {
        StringBuilder builder = new StringBuilder();
        builder.append("待人工确认的草案依据（SIMULATED，非实测）：规则层对运行 ").append(runId)
                .append(" 第 ").append(stepNo).append(" 步状态（")
                .append(String.format("%.1f℃", state.getTemperatureC())).append(' ')
                .append(String.format("湿度%.0f%%", state.getAirHumidityPct())).append(' ')
                .append(String.format("土壤%.0f%%", state.getSoilMoisturePct())).append(' ')
                .append("风险").append(state.getRiskLevel()).append("）独立重算，提出 ")
                .append(prescription.getActions().size()).append(" 条建议动作。");
        if (plan != null && plan.getRiskLevel() != null) {
            builder.append(" 决策摘要：").append(plan.getSummary()).append('。');
        }
        builder.append(" 非该运行已执行的决策。");
        return builder.toString();
    }

    private int currentStep(long runId) {
        try {
            AgentRunResponse run = runService.getSummary(runId).getRun();
            return run == null || run.getCurrentStep() == null ? 0 : run.getCurrentStep().intValue();
        } catch (RuntimeException ignored) {
            // 步号只用于引用标注，取不到就退化为 0，不影响处方本身。
            return 0;
        }
    }

    private Map<String, Object> unavailable(String note) {
        Map<String, Object> output = new LinkedHashMap<String, Object>();
        output.put("available", Boolean.FALSE);
        output.put("executed", Boolean.FALSE);
        output.put("source", "SIMULATED");
        output.put("note", note);
        return output;
    }

    /** 只列被建议开启的设备，用 "proposed" 命名避免被读成"已执行的动作"。 */
    private List<Map<String, Object>> proposedDeviceStates(DecisionPlan plan) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        if (plan == null || plan.getCommands() == null) {
            return rows;
        }
        for (DeviceCommand command : plan.getCommands()) {
            if (!command.isTargetOn()) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("device", command.getDeviceCode());
            row.put("deviceLabel", AgentDeviceCodes.label(command.getDeviceCode()));
            row.put("ruleCode", command.getRuleCode());
            row.put("resourceCode", command.getResourceCode());
            row.put("resourceAmount", command.getResourceAmount() == null
                    ? null : command.getResourceAmount().stripTrailingZeros().toPlainString());
            row.put("urgent", Boolean.valueOf(command.isUrgent()));
            rows.add(row);
        }
        return rows;
    }

    /**
     * 取处方依据。
     *
     * <p><b>没有 topic 时用默认水肥主题兜底，而不是直接不检索</b>（2026-09-26 修正）。
     * 原因是实测暴露的一个自相矛盾：本工具的 {@code description} 宣称能回答
     * 「浇多少水、追什么肥、用多少量」，规划步据此认为一个工具就够了、于是**不再调用
     * {@code knowledge.search}**；可实测那条路径拿到的只有参数登记层的几个数
     * （灌溉下限、追肥总量），标准/规程里的**逐生育期用量、肥料品种、施用节点**一条都进不来。
     * 结果就是：知识库里明明有了标准条文，回答里却依旧是"品种与配比依据不足"。</p>
     *
     * <p>两条修法里选了"让工具真的把依据取回来"而不是"把 description 写窄、指望规划步再调一次"：
     * 后者依赖模型每次都不偷懒，前者把能力做实在工具里。代价是**每次拟处方都会多一次检索**，
     * 且检索主题固定偏向水肥——对于纯粹问"棚里现在怎么样"的场景属轻微噪声，
     * 换取的是一份水肥处方必定带着它的知识依据。</p>
     */
    private RetrievalResult retrieveEvidence(Map<String, Object> input) {
        String topic = DEFAULT_TOPIC;
        String crop = null;
        if (input != null) {
            Object rawTopic = input.get("topic");
            String provided = rawTopic == null ? "" : String.valueOf(rawTopic).trim();
            if (!provided.isEmpty()) {
                topic = provided;
            }
            crop = input.get("crop") == null ? null : String.valueOf(input.get("crop"));
        }
        return retriever.retrieve(topic, crop, EVIDENCE_TOP_N);
    }

    private Long readRunId(Map<String, Object> input) {
        if (input == null) {
            return null;
        }
        Object raw = input.get("runId");
        if (raw instanceof Number) {
            return Long.valueOf(((Number) raw).longValue());
        }
        if (raw instanceof String) {
            try {
                return Long.valueOf(Long.parseLong(((String) raw).trim()));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
