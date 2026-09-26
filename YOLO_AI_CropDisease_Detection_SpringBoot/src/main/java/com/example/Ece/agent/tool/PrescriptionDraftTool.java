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

    private final AgentRunService runService;
    private final PrescriptionService prescriptionService;
    private final TomatoDecisionPolicy decisionPolicy;
    private final KnowledgeRetriever retriever;
    private final CitationFormatter citationFormatter;
    private final PlatformSnapshotEvidence evidence;

    public PrescriptionDraftTool(AgentRunService runService, PrescriptionService prescriptionService,
                                 TomatoDecisionPolicy decisionPolicy, KnowledgeRetriever retriever,
                                 CitationFormatter citationFormatter, PlatformSnapshotEvidence evidence) {
        this.runService = runService;
        this.prescriptionService = prescriptionService;
        this.decisionPolicy = decisionPolicy;
        this.retriever = retriever;
        this.citationFormatter = citationFormatter;
        this.evidence = evidence;
    }

    public String name() {
        return NAME;
    }

    public String description() {
        return "依据当前温室状态与确定性规则层，拟一份**待人工确认**的处置处方草案："
                + "含结论、建议动作、注意事项与风险等级，可附带知识库依据。"
                + "用于回答「那我该怎么处理」「现在要不要通风灌溉」这类问题。"
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
        DecisionPlan plan = decisionPolicy.decide(state);
        RetrievalResult retrieval = retrieveEvidence(input);
        List<ScoredChunk> kbEvidence = retrieval == null
                ? new ArrayList<ScoredChunk>() : retrieval.getItems();
        Prescription prescription = prescriptionService.draft(state, plan, kbEvidence);

        // 引用依据：规则层基于该快照的推演。**不是**把草案当事实引用。
        List<ScoredChunk> items = evidence.items(runId, stepNo, "番茄", TITLE,
                basisText(runId, stepNo, state, plan, prescription));

        Map<String, Object> output = new LinkedHashMap<String, Object>();
        output.put("available", Boolean.TRUE);
        output.put("runId", runId);
        output.put("conclusion", prescription.getConclusion());
        output.put("actions", prescription.getActions());
        output.put("cautions", prescription.getCautions());
        output.put("requiresManualConfirm", Boolean.valueOf(prescription.isRequiresManualConfirm()));
        output.put("riskLevel", state.getRiskLevel());
        output.put("proposedDeviceStates", proposedDeviceStates(plan));
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
                + "涉及药剂时须遵循当地登记与用药规范并人工确认。"
                // 草案要点必须走 note：证据块里每条来源只截前 160 字，动作清单放不进引用正文。
                // note 是本编排层唯一完整传给模型的工具输出通道（见 AgentOrchestrator 的 history.add 分支）。
                + "（草案要点：规则层对第 " + stepNo + " 步状态独立重算，建议动作 "
                + compactActions(plan) + "。）");
        return output;
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

    private RetrievalResult retrieveEvidence(Map<String, Object> input) {
        if (input == null) {
            return null;
        }
        Object rawTopic = input.get("topic");
        String topic = rawTopic == null ? "" : String.valueOf(rawTopic).trim();
        if (topic.isEmpty()) {
            return null;
        }
        String crop = input.get("crop") == null ? null : String.valueOf(input.get("crop"));
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
