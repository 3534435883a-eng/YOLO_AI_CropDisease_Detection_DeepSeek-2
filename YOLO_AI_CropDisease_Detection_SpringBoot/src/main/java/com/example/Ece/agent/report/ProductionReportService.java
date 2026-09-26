package com.example.Ece.agent.report;

import com.example.Ece.agent.eco.SoilParameters;
import com.example.Ece.agent.eval.EvaluationBatch;
import com.example.Ece.agent.eval.EvaluationOutcome;
import com.example.Ece.agent.eval.EvaluationStrategy;
import com.example.Ece.agent.eval.PerformanceEvaluationService;
import com.example.Ece.agent.parameter.ParameterSourceService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 生产规划报告生成：把平台上**已有的**评测矩阵、逐日序列与参数出处登记组装成一份可导出的文档。
 *
 * <p><b>这不是新造能力，是组装已有能力。</b>全季产量/成本/利润/风险已在评测矩阵里，
 * 物候进程已在逐日序列里，出处与可信度已在参数登记表里——缺的只是把它们拼成
 * 一份人能读懂、能拿去用的文档。这也是赛题"从查得到向用得上跨越"最直接的落点。</p>
 *
 * <p><b>诚实是这份报告的功能，不是修辞。</b>报告固定包含"数据可信度"与"待人工确认"两节：
 * 前者从 {@link ParameterSourceService} **实时取数**（多少参数带出处、多少未核实），
 * 后者列出必须人工决策的事项。报告全文标注为场景推演，不声称预测精度。</p>
 */
@Service
public class ProductionReportService {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** 逐日序列在报告里的抽样间隔（天）。120 天全列会让报告不可读。 */
    private static final int SERIES_SAMPLE_DAYS = 15;

    private final PerformanceEvaluationService evaluationService;
    private final ParameterSourceService parameterSourceService;

    public ProductionReportService(PerformanceEvaluationService evaluationService,
                                   ParameterSourceService parameterSourceService) {
        this.evaluationService = evaluationService;
        this.parameterSourceService = parameterSourceService;
    }

    /**
     * 生成报告（Markdown）。
     *
     * @param batchId 批次号，可为空（按 seed/days 自动命名）
     * @param seed    天气相位种子；同种子同参数必然逐值复现，报告因此可复算
     * @param days    推演天数
     */
    public String renderMarkdown(String batchId, long seed, int days) {
        EvaluationBatch batch = evaluationService.runBatch(batchId, seed, days);
        Map<EvaluationStrategy, EvaluationOutcome> outcomes = batch.getOutcomes();

        StringBuilder md = new StringBuilder();
        header(md, batch);
        overview(md, batch);
        comparison(md, outcomes);
        recommendation(md, outcomes);
        phenology(md, outcomes);
        risks(md, outcomes);
        provenance(md);
        manualConfirm(md);
        return md.toString();
    }

    private void header(StringBuilder md, EvaluationBatch batch) {
        md.append("# 番茄温室生产规划报告（场景推演）\n\n");
        md.append("> **本报告全部数值为场景推演（SIMULATED），不是现场实测，不得作为工程设定值或真实耗量。**\n")
                .append("> 生成时间：").append(LocalDateTime.now(ZoneOffset.UTC).format(TIMESTAMP)).append(" UTC")
                .append("　批次：`").append(batch.getBatchId()).append('`')
                .append("　种子：`").append(batch.getSeed()).append('`')
                .append("　推演天数：").append(batch.getDays())
                .append("　服务端耗时：").append(batch.getElapsedMillis()).append(" ms\n\n");
        md.append("同种子、同参数、同代码版本必然逐值复现，因此本报告的数字可被复核。\n\n");
    }

    private void overview(StringBuilder md, EvaluationBatch batch) {
        md.append("## 一、生产基本情况\n\n");
        md.append("| 项 | 值 |\n|---|---|\n");
        md.append("| 温室 | 8 号番茄温室（双跨薄膜棚） |\n");
        md.append("| 棚体尺寸 | 26 m × 13 m（设计值，非实地测绘） |\n");
        md.append("| 种植床 | 4 条 × 21 m × 1.7 m = ")
                .append(format(SoilParameters.BED_AREA_M2, 1)).append(" m² |\n");
        md.append("| 作物 | 番茄（半机理生长模型） |\n");
        md.append("| 推演周期 | ").append(batch.getDays())
                .append(" 天（15 分钟步长，共 ").append(batch.getDays() * 96).append(" 步） |\n");
        md.append("| 受控设备 | 9 类：滴灌、通风、屋窗、排风、湿帘、遮阳、补光、环流、CO₂ 补给 |\n\n");
    }

    private void comparison(StringBuilder md, Map<EvaluationStrategy, EvaluationOutcome> outcomes) {
        md.append("## 二、方案对比（同初值、同天气相位、同步长）\n\n");
        md.append("差异只来自「谁在做决策」，因此可直接比较。单位面积产量按种植床面积折算。\n\n");
        md.append("| 方案 | 果实干重 | 商品产量 kg | kg/m² | 水 m³ | 电 kWh | 成本元 | 利润元 |\n");
        md.append("|---|---|---|---|---|---|---|---|\n");
        for (EvaluationStrategy strategy : EvaluationStrategy.values()) {
            EvaluationOutcome o = outcomes.get(strategy);
            if (o == null) {
                continue;
            }
            md.append("| ").append(strategy.getLabel())
                    .append(" | ").append(format(o.getWFruit(), 2))
                    .append(" | ").append(format(o.getMarketableYieldKg(), 1))
                    .append(" | ").append(format(o.getMarketableYieldKg() / SoilParameters.BED_AREA_M2, 2))
                    .append(" | ").append(format(o.getWaterUsedM3(), 2))
                    .append(" | ").append(format(o.getEnergyKWh(), 2))
                    .append(" | ").append(format(o.getCostYuan(), 1))
                    .append(" | ").append(format(o.getProfitYuan(), 1))
                    .append(" |\n");
        }
        md.append("\n");
        md.append("风险与合规：\n\n");
        md.append("| 方案 | 高温 min | 高湿 min | 病害压力 | 病严重度 | 设备互斥冲突 |\n|---|---|---|---|---|---|\n");
        for (EvaluationStrategy strategy : EvaluationStrategy.values()) {
            EvaluationOutcome o = outcomes.get(strategy);
            if (o == null) {
                continue;
            }
            md.append("| ").append(strategy.getLabel())
                    .append(" | ").append(o.getHighTemperatureMinutes())
                    .append(" | ").append(o.getHighHumidityMinutes())
                    .append(" | ").append(format(o.getDiseasePressureIntegral(), 0))
                    .append(" | ").append(format(o.getFinalSeverityTotal(), 2))
                    .append(" | ").append(o.getConstraintViolations())
                    .append(" |\n");
        }
        md.append("\n");
    }

    private void recommendation(StringBuilder md, Map<EvaluationStrategy, EvaluationOutcome> outcomes) {
        // 只在**业务档位**里推荐：P4–P6 是离线消融，不是要给使用者选的场景。
        EvaluationStrategy best = null;
        EvaluationOutcome bestOutcome = null;
        for (EvaluationStrategy strategy : EvaluationStrategy.values()) {
            if (!strategy.isProductTier()) {
                continue;
            }
            EvaluationOutcome o = outcomes.get(strategy);
            if (o == null) {
                continue;
            }
            if (bestOutcome == null || o.getProfitYuan() > bestOutcome.getProfitYuan()) {
                best = strategy;
                bestOutcome = o;
            }
        }
        md.append("## 三、推荐方案与依据\n\n");
        if (best == null || bestOutcome == null) {
            md.append("本批数据不足，无法给出推荐。\n\n");
            return;
        }
        EvaluationOutcome baseline = outcomes.get(EvaluationStrategy.P2_RULE_ENGINE);
        md.append("**推荐：").append(best.getLabel()).append("**（按利润在业务档位中取最高）\n\n");
        if (bestOutcome.getMarketableYieldKg() <= 0.0) {
            // 番茄坐果在推演期后段（约第 50 天后）才发生，短周期下各档商品产量都是 0，
            // 此时"按利润取最高"比较的其实只是资源成本——必须说明，否则读者会以为方案无差别。
            md.append("> **注意：本批推演周期内尚无商品产量**（番茄坐果在推演期后段才发生），")
                    .append("因此上面的「最高利润」实际只反映资源成本差异，**不足以据此排产**。")
                    .append("请用完整生长季（≥ 120 天）出报告。\n\n");
        }
        md.append("- 商品产量 ").append(format(bestOutcome.getMarketableYieldKg(), 1)).append(" kg（")
                .append(format(bestOutcome.getMarketableYieldKg() / SoilParameters.BED_AREA_M2, 2)).append(" kg/m²），")
                .append("利润 ").append(format(bestOutcome.getProfitYuan(), 1)).append(" 元，")
                .append("用水 ").append(format(bestOutcome.getWaterUsedM3(), 2)).append(" m³，")
                .append("用电 ").append(format(bestOutcome.getEnergyKWh(), 2)).append(" kWh。\n");
        if (baseline != null && baseline != bestOutcome) {
            // 增量必须带符号：不带符号的「产量 46%」会被读成"只有基准的 46%"，
            // 而它其实是"+46%"。差异方向相反的档位同样要能看出来。
            md.append("- 相对规则自动调控：产量 ")
                    .append(signed((bestOutcome.getMarketableYieldKg() / Math.max(1e-9, baseline.getMarketableYieldKg()) - 1.0) * 100.0, 0))
                    .append("%、利润 ").append(signed(bestOutcome.getProfitYuan() - baseline.getProfitYuan(), 0))
                    .append(" 元。\n");
        }
        md.append("\n**必须同时看到的代价**：" );
        if (baseline != null && bestOutcome.getHighTemperatureMinutes() > baseline.getHighTemperatureMinutes()) {
            md.append("高温暴露 ").append(bestOutcome.getHighTemperatureMinutes())
                    .append(" min，比规则档的 ").append(baseline.getHighTemperatureMinutes())
                    .append(" min 高 ")
                    .append(format((bestOutcome.getHighTemperatureMinutes() * 1.0
                            / Math.max(1L, baseline.getHighTemperatureMinutes()) - 1.0) * 100.0, 0))
                    .append("%——即用热风险换产量；");
        }
        md.append("产物为**待人工确认的草案**，系统不执行任何设备操作。\n\n");
    }

    private void phenology(StringBuilder md, Map<EvaluationStrategy, EvaluationOutcome> outcomes) {
        EvaluationStrategy target = pickProductTierWithBestProfit(outcomes);
        EvaluationOutcome picked = target == null ? null : outcomes.get(target);
        if (picked == null || picked.getSeries().isEmpty()) {
            return;
        }
        md.append("## 四、生育期进程（").append(target.getLabel()).append("）\n\n");
        md.append("> 逐日序列每 ").append(SERIES_SAMPLE_DAYS)
                .append(" 天抽样；序列取的是每日最后一步（午夜）的采样值，")
                .append("温度湿度这类日内波动大的量不宜按此判断。\n\n");
        md.append("| 天 | 日期 | 生育期 | GDD | LAI | 果实干重 | 风险 |\n|---|---|---|---|---|---|---|\n");
        List<Map<String, Object>> series = picked.getSeries();
        for (int index = 0; index < series.size(); index += SERIES_SAMPLE_DAYS) {
            appendSeriesRow(md, series.get(index));
        }
        appendSeriesRow(md, series.get(series.size() - 1));
        md.append("\n");
    }

    private void appendSeriesRow(StringBuilder md, Map<String, Object> entry) {
        md.append("| ").append(entry.get("day"))
                .append(" | ").append(sampleDate(entry))
                .append(" | ").append(readableStage(entry.get("stage")))
                .append(" | ").append(format(number(entry.get("gdd")), 0))
                .append(" | ").append(format(number(entry.get("lai")), 2))
                .append(" | ").append(format(number(entry.get("wFruit")), 1))
                .append(" | ").append(entry.get("riskLevel"))
                .append(" |\n");
    }

    private void risks(StringBuilder md, Map<EvaluationStrategy, EvaluationOutcome> outcomes) {
        EvaluationStrategy target = pickProductTierWithBestProfit(outcomes);
        EvaluationOutcome picked = target == null ? null : outcomes.get(target);
        md.append("## 五、风险提示\n\n");
        if (picked == null) {
            return;
        }
        md.append("- **热风险**：高温暴露 ").append(picked.getHighTemperatureMinutes())
                .append(" min；逐步平均超温量 ")
                .append(format(picked.getMeanTemperatureExceedanceC(), 3)).append(" ℃。\n");
        md.append("- **湿风险**：高湿时长 ").append(picked.getHighHumidityMinutes())
                .append(" min；逐步平均超湿量 ")
                .append(format(picked.getMeanHumidityExceedancePct(), 3)).append(" %RH。\n");
        md.append("- **病害**：全季病害压力积分 ")
                .append(format(picked.getDiseasePressureIntegral(), 0))
                .append("，末期严重度合计 ").append(format(picked.getFinalSeverityTotal(), 2))
                .append("（百分比量纲，四类病害之和）。\n");
        md.append("- **水肥**：见下节可信度声明——养分收支尚未配平，水肥调控结论当前**不可用**。\n\n");
    }

    /**
     * 数据可信度：**从参数登记表实时取数**，而不是在报告里写死一句免责声明。
     * 这样报告引用的数字与登记状态永远一致——登记表变了，报告里的说法跟着变。
     */
    private void provenance(StringBuilder md) {
        md.append("## 六、数据可信度与边界（必读）\n\n");
        md.append("- 全部数值为**场景推演（SIMULATED）**，不接真实传感器与执行器，不是现场实测。\n");
        md.append("- 设备状态来自各步保存的推演快照，不是实时遥测；本报告**不声称预测精度**。\n");
        md.append("- **湿帘用水在评测平台未折算**；跨方案比较水耗时该项不利。\n");
        md.append("- **氮收支配平未完成**：速效氮在季内见底、养分因子长期钳在下限，")
                .append("因此**水肥调控与产量当前是解耦的**，本报告中水肥相关的方案差异不可作为调控依据。\n");
        try {
            Map<String, Object> summary = parameterSourceService.summary();
            md.append("- 本批仿真参数的出处登记（版本 `").append(summary.get("version")).append("`）：共 ")
                    .append(summary.get("total")).append(" 条，其中**带出处链接的 ")
                    .append(summary.get("citable")).append(" 条**，")
                    .append("未核实 ").append(statusCount(summary, "UNVERIFIED_LITERATURE")).append(" 条、")
                    .append("示例值 ").append(statusCount(summary, "PLACEHOLDER")).append(" 条；")
                    .append("另有 ").append(summary.get("unitMissing")).append(" 条未登记单位。\n");
            md.append("  引用参数数值时必须连版本一起引用；被登记为未核实/示例值的参数，")
                    .append("**不得作为有据可依的取值对外引用**。\n");
        } catch (RuntimeException error) {
            md.append("- 参数出处登记表当前不可查询，无法给出可信度统计。\n");
        }
        md.append("\n");
    }

    private void manualConfirm(StringBuilder md) {
        md.append("## 七、待人工确认项\n\n");
        md.append("1. 所有设备动作均为**草案**，须由管理人员现场判断后手动执行；系统不代执行。\n");
        md.append("2. 涉及药剂时，须遵循**当地农药登记范围与用药规范**并核对安全间隔期；本报告不提供药剂品种与剂量。\n");
        md.append("3. 图像识别结果**不能单独确诊**，病毒类与虫害类需实验室或专业人员确认。\n");
        md.append("4. 生产规划的实际排产需结合当地气候、品种、市场价格与用工情况另行核定。\n");
    }

    private EvaluationStrategy pickProductTierWithBestProfit(Map<EvaluationStrategy, EvaluationOutcome> outcomes) {
        EvaluationStrategy best = null;
        double bestProfit = Double.NEGATIVE_INFINITY;
        for (EvaluationStrategy strategy : EvaluationStrategy.values()) {
            EvaluationOutcome o = outcomes.get(strategy);
            if (!strategy.isProductTier() || o == null) {
                continue;
            }
            if (o.getProfitYuan() > bestProfit) {
                bestProfit = o.getProfitYuan();
                best = strategy;
            }
        }
        return best;
    }

    private Object statusCount(Map<String, Object> summary, String status) {
        Object byStatus = summary.get("byStatus");
        if (byStatus instanceof Map) {
            return ((Map<?, ?>) byStatus).get(status);
        }
        return 0;
    }

    private String sampleDate(Map<String, Object> entry) {
        Object value = entry.get("simulatedAt");
        return value == null ? "-" : String.valueOf(value);
    }

    /** 生育期枚举转中文；未知值原样返回，不猜。 */
    private String readableStage(Object stage) {
        if (stage == null) {
            return "-";
        }
        switch (String.valueOf(stage)) {
            case "SEEDLING":
                return "苗期";
            case "FLOWERING":
                return "开花期";
            case "FRUIT_SET":
                return "坐果期";
            case "FRUIT_GROWTH":
                return "果实膨大期";
            case "MATURITY":
                return "成熟期";
            default:
                return String.valueOf(stage);
        }
    }

    private double number(Object value) {
        return value instanceof Number ? ((Number) value).doubleValue() : 0.0;
    }

    private String format(double value, int scale) {
        return String.format("%." + Math.max(0, scale) + "f", Double.valueOf(value));
    }

    /** 带符号的增量：正数显式加「+」。比较类数字不带符号会被读反。 */
    private String signed(double value, int scale) {
        return (value > 0 ? "+" : "") + format(value, scale);
    }
}
