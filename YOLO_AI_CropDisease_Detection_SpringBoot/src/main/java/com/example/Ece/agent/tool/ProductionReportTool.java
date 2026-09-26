package com.example.Ece.agent.tool;

import com.example.Ece.agent.eco.SoilParameters;
import com.example.Ece.agent.eval.EvaluationBatch;
import com.example.Ece.agent.eval.EvaluationOutcome;
import com.example.Ece.agent.eval.EvaluationStrategy;
import com.example.Ece.agent.eval.PerformanceEvaluationService;
import com.example.Ece.agent.rag.ScoredChunk;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 生产规划报告工具：让用户能直接"要一份报告"。
 *
 * <p>报告正文由 {@code /ai/agent/report} 导出（Markdown），本工具只回**结论摘要 + 导出方式**——
 * 一份 120 天报告是上千字的文档，塞进工具输出既没有意义（模型在证据块里只看到 160 字），
 * 也会挤掉真正的上下文。</p>
 *
 * <p><b>走标准证据通道</b>：像平台工具一样产出可核对引用（{@code agent_report} 来源、
 * 批次号与种子作为可回查标识），否则编排层的完成条件不认、只能拒答。
 * 引用正文把推荐方案、关键数字与代价放在最前 160 字内，确保模型看得到。</p>
 *
 * <p>权限 {@code DRAFT}：报告是**待人工确认的规划草案**，系统不执行任何设备操作。</p>
 */
@Component
public class ProductionReportTool implements AgentTool {

    public static final String NAME = "report.draft";

    private static final String SOURCE_TABLE = "agent_report";
    private static final String TITLE = "生产规划报告摘要";
    private static final String EXPORT_PATH = "/ai/agent/report";
    private static final int DEFAULT_DAYS = 120;
    private static final long DEFAULT_SEED = 20260921L;

    private final PerformanceEvaluationService evaluationService;
    private final PlatformSnapshotEvidence evidence;

    public ProductionReportTool(PerformanceEvaluationService evaluationService,
                                PlatformSnapshotEvidence evidence) {
        this.evaluationService = evaluationService;
        this.evidence = evidence;
    }

    public String name() {
        return NAME;
    }

    public String description() {
        return "生成番茄温室生产规划报告：给出各方案的全季产量/成本/利润/风险对比、推荐方案与依据、"
                + "生育期进程、风险提示与数据可信度声明。用于回答「给我出一份生产规划」「该选哪套方案」"
                + "「这季大概什么收成」这类问题。报告是**场景推演草案**，需人工确认，系统不执行任何操作。";
    }

    public ToolPermission permission() {
        return ToolPermission.DRAFT;
    }

    public String inputSchemaJson() {
        return "{\"type\":\"object\",\"properties\":{"
                + "\"seed\":{\"type\":\"integer\",\"description\":\"天气相位种子，可选；同种子结果可复算\"},"
                + "\"days\":{\"type\":\"integer\",\"description\":\"推演天数，默认 120\"}},"
                + "\"required\":[]}";
    }

    public Map<String, Object> execute(Map<String, Object> input) throws ToolException {
        long seed = readLong(input, "seed", DEFAULT_SEED);
        int days = (int) readLong(input, "days", DEFAULT_DAYS);
        if (days <= 0) {
            days = DEFAULT_DAYS;
        }
        EvaluationBatch batch = evaluationService.runBatch(null, seed, days);
        Map<EvaluationStrategy, EvaluationOutcome> outcomes = batch.getOutcomes();

        EvaluationStrategy best = null;
        EvaluationOutcome bestOutcome = null;
        for (EvaluationStrategy strategy : EvaluationStrategy.values()) {
            EvaluationOutcome candidate = outcomes.get(strategy);
            if (!strategy.isProductTier() || candidate == null) {
                continue;
            }
            if (bestOutcome == null || candidate.getProfitYuan() > bestOutcome.getProfitYuan()) {
                best = strategy;
                bestOutcome = candidate;
            }
        }

        Map<String, Object> output = new LinkedHashMap<String, Object>();
        output.put("available", Boolean.TRUE);
        output.put("batchId", batch.getBatchId());
        output.put("seed", Long.valueOf(batch.getSeed()));
        output.put("days", Integer.valueOf(batch.getDays()));
        output.put("exportPath", EXPORT_PATH + "?seed=" + batch.getSeed() + "&days=" + batch.getDays());
        output.put("executed", Boolean.FALSE);

        if (best == null || bestOutcome == null) {
            output.put("available", Boolean.FALSE);
            output.put("note", "本批评测数据不足，无法生成推荐方案。");
            return output;
        }

        EvaluationOutcome baseline = outcomes.get(EvaluationStrategy.P2_RULE_ENGINE);
        String summary = summaryText(best, bestOutcome, baseline, batch);
        output.put("recommendedStrategy", best.name());
        output.put("recommendedLabel", best.getLabel());
        output.put("marketableYieldKg", Double.valueOf(bestOutcome.getMarketableYieldKg()));
        output.put("yieldPerSquareMeter", Double.valueOf(bestOutcome.getMarketableYieldKg() / SoilParameters.BED_AREA_M2));
        output.put("profitYuan", Double.valueOf(bestOutcome.getProfitYuan()));
        output.put("waterUsedM3", Double.valueOf(bestOutcome.getWaterUsedM3()));
        output.put("energyKWh", Double.valueOf(bestOutcome.getEnergyKWh()));
        output.put("highTemperatureMinutes", Long.valueOf(bestOutcome.getHighTemperatureMinutes()));
        output.put("summary", summary);

        // 可核对引用：来源标识用批次号与种子——任何人可用同一 URL 复算得到同一份报告，
        // 而不是给一个 run=0;step=0 这种无意义标识（那会把"可回查"变成假的）。
        List<ScoredChunk> items = evidence.items(
                batch.getSeed(), 0, "番茄", TITLE, summary,
                "SIM-REPORT-" + batch.getBatchId().hashCode(),
                "场景推演生产规划报告（SIMULATED，不是实测）",
                "batch=" + batch.getBatchId() + ";seed=" + batch.getSeed() + ";days=" + batch.getDays());
        output.put("items", items);
        output.put("citations", evidence.citations(items));
        output.put("lowScore", Boolean.FALSE);
        output.put("source", "SIMULATED");
        output.put("stepSummary", NAME + " 生成报告摘要（推荐 " + best.getLabel() + "）");
        output.put("note", "本报告为**待人工确认的场景推演草案**，全部数值为 SIMULATED，不是现场实测，"
                + "不得作为工程设定值或真实耗量。系统不执行任何设备操作。"
                + "涉及药剂须遵循当地登记与用药规范；水肥调控结论当前不可用（氮收支未配平）。"
                + "完整报告请通过导出接口获取。");
        return output;
    }

    /** 摘要正文，**把最关键的事实放在最前 160 字内**（模型在证据块里只看到这么多）。 */
    private String summaryText(EvaluationStrategy best, EvaluationOutcome outcome,
                               EvaluationOutcome baseline, EvaluationBatch batch) {
        StringBuilder text = new StringBuilder();
        text.append("SIMULATED 场景推演，非实测。推荐「").append(best.getLabel()).append("」：")
                .append(batch.getDays()).append(" 天商品产量 ")
                .append(format(outcome.getMarketableYieldKg(), 1)).append(" kg（")
                .append(format(outcome.getMarketableYieldKg() / SoilParameters.BED_AREA_M2, 1)).append(" kg/m²）、")
                .append("利润 ").append(format(outcome.getProfitYuan(), 0)).append(" 元。");
        if (baseline != null && baseline != outcome) {
            // 增量必须带符号。实测踩过：此处原用无符号 format，模型转述成
            // 「相对规则档产量记作 46%」——把 +46% 说成了基准的 46%，读数方向反了。
            text.append("相对规则档产量 ")
                    .append(signed((outcome.getMarketableYieldKg() / Math.max(1e-9, baseline.getMarketableYieldKg()) - 1.0) * 100.0, 0))
                    .append("%、高温暴露 ").append(outcome.getHighTemperatureMinutes())
                    .append(" min（规则档 ").append(baseline.getHighTemperatureMinutes()).append(" min）。");
        }
        text.append("批次 ").append(batch.getBatchId()).append("，种子 ").append(batch.getSeed())
                .append("，可用同一 URL 复算。");
        return text.toString();
    }

    private long readLong(Map<String, Object> input, String key, long fallback) {
        if (input == null) {
            return fallback;
        }
        Object raw = input.get(key);
        if (raw instanceof Number) {
            return ((Number) raw).longValue();
        }
        if (raw instanceof String) {
            try {
                return Long.parseLong(((String) raw).trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private String format(double value, int scale) {
        return String.format("%." + Math.max(0, scale) + "f", Double.valueOf(value));
    }

    /** 带符号的增量：正数显式加「+」。比较类数字不带符号会被读反。 */
    private String signed(double value, int scale) {
        return (value > 0 ? "+" : "") + format(value, scale);
    }
}
