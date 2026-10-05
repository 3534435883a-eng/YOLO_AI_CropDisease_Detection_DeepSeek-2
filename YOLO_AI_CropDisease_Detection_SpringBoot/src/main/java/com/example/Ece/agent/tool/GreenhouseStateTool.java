package com.example.Ece.agent.tool;

import com.example.Ece.agent.dto.AgentRunResponse;
import com.example.Ece.agent.dto.AgentRunSummaryResponse;
import com.example.Ece.agent.model.AgentDeviceCodes;
import com.example.Ece.agent.rag.ScoredChunk;
import com.example.Ece.agent.service.AgentRunService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 温室状态工具：让智能体"看得见棚"。
 *
 * <p>在此之前智能体只有知识检索与视觉映射两个工具，用户问"我棚里现在怎么样"它答不出。</p>
 *
 * <p><b>为什么必须产出可核对引用</b>：编排层的完成条件只认"工具返回了 citations 且不是 lowScore"，
 * 守门还要求一份非空的 {@code List<ScoredChunk>}。只回状态字段的工具会永远停在
 * "未提供可用证据"→拒答（2026-09-26 端到端实测确认过这个失败）。
 * 因此这里通过 {@link PlatformSnapshotEvidence} 把快照包成一条**有编号、有出处、可回查**的事实来源，
 * 与知识库条目走完全相同的通道。</p>
 *
 * <p><b>数据性质由工具固定标注</b>，不交给模型判断：来源名写明"SIMULATED，非现场传感器实测"，
 * 正文末尾也复述一次。项目全程不接真实传感器，若让模型把这些值当实测报给农户，
 * 就是把仿真冒充实测。</p>
 */
@Component
public class GreenhouseStateTool implements AgentTool {

    public static final String NAME = "platform.greenhouseState";

    private static final int MAX_ALERTS = 10;
    private static final int MAX_RESOURCES = 12;
    private static final String TITLE = "温室环境快照";

    private final AgentRunService runService;
    private final PlatformSnapshotEvidence evidence;

    public GreenhouseStateTool(AgentRunService runService, PlatformSnapshotEvidence evidence) {
        this.runService = runService;
        this.evidence = evidence;
    }

    public String name() {
        return NAME;
    }

    public String description() {
        return "读取当前番茄温室仿真运行的状态：环境指标、九类设备开关、未处理告警、虚拟资源余量，"
                + "以及最近一次决策摘要。用于回答「我棚里现在怎么样」「当前风险高不高」"
                + "「哪些设备开着」这类问题。注意：这是场景推演状态，不是现场传感器实测。";
    }

    public ToolPermission permission() {
        return ToolPermission.READ_ONLY;
    }

    public String inputSchemaJson() {
        return "{\"type\":\"object\",\"properties\":{"
                + "\"runId\":{\"type\":\"integer\",\"description\":\"模拟运行编号，可选；缺省读取当前活动运行\"}},"
                + "\"required\":[]}";
    }

    public Map<String, Object> execute(Map<String, Object> input) throws ToolException {
        Long runId = readRunId(input);
        if (runId == null) {
            AgentRunResponse active = runService.getActiveRun();
            if (active == null || active.getId() == null) {
                // 无运行不是错误，是事实。**刻意不给引用**：没有任何事实来源可引，
                // 编排层据此走拒答路径是正确的——总比编一个状态好。
                Map<String, Object> empty = new LinkedHashMap<String, Object>();
                empty.put("available", Boolean.FALSE);
                empty.put("source", "SIMULATED");
                empty.put("note", "当前没有进行中的温室模拟运行，无法提供棚内状态。"
                        + "请先在智能体指挥中心创建并启动一次模拟运行。");
                return empty;
            }
            runId = active.getId();
        }

        AgentRunSummaryResponse summary = runService.getSummary(runId);
        int stepNo = summary.getRun() == null || summary.getRun().getCurrentStep() == null
                ? 0 : summary.getRun().getCurrentStep().intValue();
        String text = snapshotText(summary, runId, stepNo);
        List<ScoredChunk> items = evidence.items(runId, stepNo, "番茄", TITLE, text);

        Map<String, Object> output = new LinkedHashMap<String, Object>();
        output.put("available", Boolean.TRUE);
        output.put("runId", runId);
        output.put("runCode", summary.getRun() == null ? null : summary.getRun().getRunCode());
        output.put("runStatus", summary.getRun() == null ? null : summary.getRun().getStatus());
        output.put("currentStep", Integer.valueOf(stepNo));
        output.put("totalSteps", summary.getRun() == null ? null : summary.getRun().getTotalSteps());
        output.put("simulatedAt", summary.getRun() == null ? null : summary.getRun().getSimulatedAt());
        output.put("greenhouse", summary.getRun() == null ? null : summary.getRun().getGreenhouseName());
        output.put("tickMinutes", summary.getRun() == null ? null : summary.getRun().getTickMinutes());
        output.put("profile", summary.getRun() == null ? null : summary.getRun().getProfile());
        output.put("currentState", summary.getCurrentState());
        output.put("metrics", summary.getMetrics());
        output.put("devices", summary.getDevices());
        output.put("alerts", limit(summary.getAlerts(), MAX_ALERTS));
        output.put("resources", limit(summary.getResources(), MAX_RESOURCES));
        output.put("lastDecisionSummary", summary.getStrategySummary());
        // 走标准证据通道：items 给守门，citations 给编排层与模型。
        output.put("items", items);
        output.put("citations", evidence.citations(items));
        output.put("lowScore", Boolean.FALSE);
        output.put("source", "SIMULATED");
        output.put("stepSummary", NAME + " 取到第 " + stepNo + " 步快照");
        // 术语只用「推演值 / 实测 / 实时遥测」：note 与快照正文必须同一套词。
        // 此前 note 写"现场传感器实测"而正文写"推演值"，模型把两者混成
        // "不是现场传感器推演显示"这种读不通的句子（真实会话里复现过两次）。
        output.put("note", "以上均为场景推演值（SIMULATED），不是实测，不得当作实测数据引用；"
                + "设备状态取自各步保存的推演快照，不是实时遥测。");
        output.put("inputDigest", "greenhouseState|run=" + runId + "|step=" + stepNo);
        return output;
    }

    /**
     * 构造快照正文。
     *
     * <p><b>字数预算是硬约束</b>：模型在证据块里只看得到 {@code CitationFormatter} 截出的前
     * {@code SNIPPET_LIMIT = 160} 字（snippet）。初版把"这是 SIMULATED 非实测"的说明写在**末尾**，
     * 结果在端到端测试里被整段截掉——安全限定语恰恰是最不能被截的那部分。
     * 因此这里改为：**限定语最前 → 运行/步号 → 环境指标 → 设备状态**，
     * 更少被问到的告警与资源放最后，被截掉也不影响常见问题。</p>
     *
     * <p>数值直接取服务为界面渲染好的 metrics 列表（已有 label/value/unit），
     * 不去猜 currentState 的键名。</p>
     */
    private String snapshotText(AgentRunSummaryResponse summary, long runId, int stepNo) {
        StringBuilder builder = new StringBuilder();
        // 术语只用「推演值 / 实测」这一对：实测里模型把"场景推演"与"现场传感器实测"
        // 混成了"现场传感器推演显示"这样的句子。相邻术语越少，混淆越少。
        builder.append("SIMULATED 推演值，不是实测。运行 ").append(runId)
                .append(" 第 ").append(stepNo).append(" 步：");
        appendMetrics(builder, summary.getMetrics());
        appendDevices(builder, summary.getDevices());
        appendAlerts(builder, summary.getAlerts());
        return builder.toString();
    }

    /**
     * 指标必须**带标签**。
     *
     * <p>初版为省字数只输出数值，端到端测试里模型拿到一串无标注数字，
     * 只能靠顺序猜，并明确报告"40.45% 未标注含义，我不做推断"——
     * 证据含糊是工具的问题，不该让模型去猜。标签很短，放得下。</p>
     */
    private void appendMetrics(StringBuilder builder, List<Map<String, Object>> metrics) {
        if (metrics == null || metrics.isEmpty()) {
            return;
        }
        for (Map<String, Object> metric : metrics) {
            builder.append(text(metric, "label", "code"))
                    .append(text(metric, "value", null));
            String unit = text(metric, "unit", null);
            if (!unit.isEmpty()) {
                builder.append(unit);
            }
            builder.append(' ');
        }
        builder.append('；');
    }

    /** 设备按开/关分组，省字数且更利于模型判断"换气通道通不通"。 */
    private void appendDevices(StringBuilder builder, List<Map<String, Object>> devices) {
        if (devices == null || devices.isEmpty()) {
            return;
        }
        StringBuilder on = new StringBuilder();
        StringBuilder off = new StringBuilder();
        for (Map<String, Object> device : devices) {
            String code = text(device, "code", "deviceCode");
            if (code.isEmpty()) {
                continue;
            }
            StringBuilder target = "ON".equalsIgnoreCase(text(device, "actualState", "enabled")) ? on : off;
            if (target.length() > 0) {
                target.append('/');
            }
            target.append(AgentDeviceCodes.label(code));
        }
        builder.append("设备");
        if (on.length() > 0) {
            builder.append("开：").append(on);
        }
        if (off.length() > 0) {
            builder.append(on.length() > 0 ? " " : "开：无 ").append("关：").append(off);
        }
        builder.append('；');
    }

    private void appendAlerts(StringBuilder builder, List<Map<String, Object>> alerts) {
        if (alerts == null || alerts.isEmpty()) {
            builder.append("未处理告警 0 条。");
            return;
        }
        builder.append("未处理告警 ").append(alerts.size()).append(" 条：");
        for (Map<String, Object> alert : limit(alerts, MAX_ALERTS)) {
            builder.append(text(alert, "message", "summary")).append('（')
                    .append(text(alert, "severity", null)).append("）");
        }
        builder.append('。');
    }

    /** 宽容取字段：界面与后端的键名历史上不完全一致，缺失时回退到备用键，再取不到就留空。 */
    private String text(Map<String, Object> row, String key, String fallbackKey) {
        Object value = row.get(key);
        if (value == null && fallbackKey != null) {
            value = row.get(fallbackKey);
        }
        return value == null ? "" : String.valueOf(value);
    }

    /** 模型可能传字符串数字，这里统一容忍；非法值按"未指定"处理。 */
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

    private List<Map<String, Object>> limit(List<Map<String, Object>> rows, int max) {
        if (rows == null) {
            return new ArrayList<Map<String, Object>>();
        }
        return rows.size() <= max ? rows : new ArrayList<Map<String, Object>>(rows.subList(0, max));
    }
}
