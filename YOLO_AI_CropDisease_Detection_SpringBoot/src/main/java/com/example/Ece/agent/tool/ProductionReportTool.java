package com.example.Ece.agent.tool;

import com.example.Ece.agent.eco.SoilParameters;
import com.example.Ece.agent.eval.EvaluationBatch;
import com.example.Ece.agent.eval.EvaluationOutcome;
import com.example.Ece.agent.eval.EvaluationStrategy;
import com.example.Ece.agent.eval.PerformanceEvaluationService;
import com.example.Ece.agent.rag.CitationFormatter;
import com.example.Ece.agent.rag.KnowledgeRetriever;
import com.example.Ece.agent.rag.RetrievalResult;
import com.example.Ece.agent.rag.ScoredChunk;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /**
     * 报告附带的默认检索主题（2026-09-27 新增）。
     *
     * <p><b>为什么报告要自带检索</b>：实测问「给我出一份这季番茄的生产规划报告」时，工具链是
     * {@code report.draft, report.draft}——规划步只调本工具、**从不调 knowledge.search**，
     * 于是整份回答只引用了 1 条（报告快照本身），并写出"水肥结论当前不可用"，
     * 而库里当时水肥与植保条目都是齐的。这与 {@code prescription.draft} 的情况**完全同型**：
     * 工具宣称能出报告，规划步就不再检索，报告于是只剩推演数字、没有可落地依据。</p>
     *
     * <p>主题覆盖报告的三个下游分支：水肥调控、病虫害防治、栽培（农事）管理。</p>
     */
    private static final String[] DEFAULT_TOPICS = {
            "番茄 水肥管理 灌溉制度 施肥制度 追肥 灌水量",
            "番茄 病虫害防治 农业防治 物理防治 生物防治 药剂",
            "番茄 环境调控 温度 湿度 通风 遮阳 补光"
    };

    private final PerformanceEvaluationService evaluationService;
    private final PlatformSnapshotEvidence evidence;
    private final KnowledgeRetriever retriever;
    private final CitationFormatter citationFormatter;

    public ProductionReportTool(PerformanceEvaluationService evaluationService,
                                PlatformSnapshotEvidence evidence,
                                KnowledgeRetriever retriever,
                                CitationFormatter citationFormatter) {
        this.evaluationService = evaluationService;
        this.evidence = evidence;
        this.retriever = retriever;
        this.citationFormatter = citationFormatter;
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
        List<ScoredChunk> items = new ArrayList<ScoredChunk>(evidence.items(
                batch.getSeed(), 0, "番茄", TITLE, summary,
                "SIM-REPORT-" + batch.getBatchId().hashCode(),
                "场景推演生产规划报告（SIMULATED，不是实测）",
                "batch=" + batch.getBatchId() + ";seed=" + batch.getSeed() + ";days=" + batch.getDays()));

        // 报告的可落地依据：水肥怎么调、病虫害怎么防、环境怎么控——这三件事都不在推演数值里，
        // 而在知识库。判据沿用检索层的 lowScore，与 knowledge.search **完全一致**：
        // 相关性不足的片段不占引用编号，免得"编了号却撑不住结论"。
        //
        // **必须按分支分开检索**（2026-09-27 实测）：最初用一条混合主题的词串检索，
        // 结果被"水肥"词占满——模型据此写出"知识库缺少温度管理、环境调控和病虫害防治依据"，
        // 而这三类条目在库里都有。一次检索只能命中一个分支，多分支必须多次检索后合并。
        Object rawTopic = input == null ? null : input.get("topic");
        String explicitTopic = rawTopic == null ? "" : String.valueOf(rawTopic).trim();
        String[] topics = explicitTopic.isEmpty() ? DEFAULT_TOPICS : new String[]{explicitTopic};
        List<ScoredChunk> kbEvidence = new ArrayList<ScoredChunk>();
        Set<String> seenChunks = new HashSet<String>();
        boolean kbLowScore = true;
        for (String topic : topics) {
            RetrievalResult retrieval = retriever == null ? null : retriever.retrieve(topic, "番茄", 3);
            if (retrieval == null || retriever.isLowScore(retrieval)) {
                continue;
            }
            kbLowScore = false;
            for (ScoredChunk item : retrieval.getItems()) {
                // 同一块可能被多个分支命中，按引用去重键（来源表|来源ID|片段号）去重
                String key = item.getChunk().getSourceTable() + "|" + item.getChunk().getSourceId()
                        + "|" + item.getChunk().getChunkNo();
                if (seenChunks.add(key)) {
                    kbEvidence.add(item);
                }
            }
        }
        if (!kbLowScore) {
            items.addAll(kbEvidence);
        }
        output.put("items", items);
        output.put("citations", citationFormatter == null
                ? evidence.citations(items) : citationFormatter.toCitations(items));
        output.put("kbEvidence", citationFormatter == null
                ? kbEvidence : citationFormatter.toCitations(kbEvidence));
        output.put("kbLowScore", Boolean.valueOf(kbLowScore));
        // 报告快照本身是权威事实来源（批次号 + 种子可复算），故整体不判低分；
        // 知识库片段只在通过同一判据时才并入编号，两个层次不混。
        output.put("lowScore", Boolean.FALSE);
        output.put("source", "SIMULATED");
        output.put("stepSummary", NAME + " 生成报告摘要（推荐 " + best.getLabel() + "）");
        // 2026-09-27 修：原文写死"水肥调控结论当前不可用（氮收支未配平）"。
        // 那是氮池见底时期的状态；此后补了矿化项、需求侧改为按累积吸收曲线，收支已自洽，
        // 而这句仍在**主动指示模型说"水肥给不出"**——实测报告回答里确实照抄了这句。
        // 现在改为把模型指向随本工具返回的知识库条目，并如实保留"推演值 + 待实测替换"的边界。
        output.put("note", "本报告为**待人工确认的场景推演草案**，全部数值为 SIMULATED，不是现场实测，"
                + "不得作为工程设定值或真实耗量。系统不执行任何设备操作，涉及药剂须遵循当地登记与用药规范。"
                + "**报告只给方案层面的取舍；具体的灌溉、施肥与病虫害防治，请引用随本工具一并返回的"
                + "知识库条目**（编号已与报告快照统一编号），不要用推演数值反推用量——"
                + "推演数值不是实测，且氮池定标与矿化量仍待实测替换。完整报告请通过导出接口获取。");
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
