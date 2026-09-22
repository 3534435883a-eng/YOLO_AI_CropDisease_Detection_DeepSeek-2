package com.example.Ece.agent.eval;

import com.example.Ece.agent.crop.TomatoCropGrowthModel;
import com.example.Ece.agent.eco.ManagementEconomicsModel;
import com.example.Ece.agent.eco.PestDiseaseEpidemicModel;
import com.example.Ece.agent.eco.SoilWaterNutrientModel;
import com.example.Ece.agent.engine.TomatoDecisionPolicy;
import com.example.Ece.agent.engine.TomatoSimulationEngine;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 指标矩阵报告：打印四档对照的完整数字，既是人工核对手段，也是参赛材料"应用成效"的取证脚本。
 * 断言只保留"必须成立"的关系，避免把报告测试写成脆弱测试。
 */
class PerformanceMatrixReportTest {

    private final PerformanceEvaluationService service = new PerformanceEvaluationService(
            new TomatoSimulationEngine(), new TomatoDecisionPolicy(), new TomatoCropGrowthModel(),
            new SoilWaterNutrientModel(), new PestDiseaseEpidemicModel(), new ManagementEconomicsModel());

    @Test
    void printsFourStrategyMatrix() {
        EvaluationBatch batch = service.runBatch("matrix-report", 20260921L, 120);
        System.out.println("=== 四档对照指标矩阵（120 天，seed=20260921）===");
        System.out.printf("%-16s %10s %10s %10s %10s %10s %10s %10s%n",
                "策略", "果实干重", "坐果率", "产量kg", "水m3", "电kWh", "成本元", "利润元");
        for (EvaluationStrategy strategy : EvaluationStrategy.values()) {
            EvaluationOutcome o = batch.getOutcomes().get(strategy);
            System.out.printf("%-16s %10.2f %10.3f %10.1f %10.2f %10.2f %10.1f %10.1f%n",
                    strategy.name(), o.getWFruit(), o.getFruitSetRate(), o.getYieldKg(),
                    o.getWaterUsedM3(), o.getEnergyKWh(), o.getCostYuan(), o.getProfitYuan());
        }
        System.out.println("--- 风险与合规 ---");
        System.out.printf("%-16s %10s %10s %10s %10s %10s%n",
                "策略", "高温min", "高湿min", "高VPD min", "病害压力", "严重度%", "冲突次数");
        for (EvaluationStrategy strategy : EvaluationStrategy.values()) {
            EvaluationOutcome o = batch.getOutcomes().get(strategy);
            System.out.printf("%-16s %10d %10d %10d %10.1f %10.2f %10d%n",
                    strategy.name(), o.getHighTemperatureMinutes(), o.getHighHumidityMinutes(),
                    o.getHighVpdMinutes(), o.getDiseasePressureIntegral(), o.getFinalSeverityTotal(),
                    o.getConstraintViolations());
        }

        EvaluationOutcome none = batch.getOutcomes().get(EvaluationStrategy.P0_NONE);
        EvaluationOutcome rule = batch.getOutcomes().get(EvaluationStrategy.P2_RULE_ENGINE);
        EvaluationOutcome agent = batch.getOutcomes().get(EvaluationStrategy.P3_AGENT);

        // 必须成立的关系：调控档用水必然多于不调控档（这是决策的代价，也是可讲的取舍）
        assertTrue(rule.getWaterUsedM3() > none.getWaterUsedM3(), "调控档应产生灌溉用水");
        // 调控档的病害压力不应高于放任档
        assertTrue(rule.getDiseasePressureIntegral() <= none.getDiseasePressureIntegral() * 1.05,
                "调控档不应比放任档更容易发病");
        // 智能体档与规则档必须在某些维度上可区分（否则"AI 档"没有存在意义）
        boolean distinguishable = Math.abs(agent.getWFruit() - rule.getWFruit()) > 1e-6
                || Math.abs(agent.getWaterUsedM3() - rule.getWaterUsedM3()) > 1e-6
                || Math.abs(agent.getEnergyKWh() - rule.getEnergyKWh()) > 1e-6
                || agent.getHighTemperatureMinutes() != rule.getHighTemperatureMinutes();
        assertTrue(distinguishable, "智能体档与规则档完全一致——沙盘推演没有产生任何差异，需检查择优逻辑");
    }
}
