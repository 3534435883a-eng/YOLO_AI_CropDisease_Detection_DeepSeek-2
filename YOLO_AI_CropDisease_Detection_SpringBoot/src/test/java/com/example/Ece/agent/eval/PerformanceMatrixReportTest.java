package com.example.Ece.agent.eval;

import com.example.Ece.agent.crop.TomatoCropGrowthModel;
import com.example.Ece.agent.eco.ManagementEconomicsModel;
import com.example.Ece.agent.eco.PestDiseaseEpidemicModel;
import com.example.Ece.agent.eco.SoilWaterNutrientModel;
import com.example.Ece.agent.engine.TomatoDecisionPolicy;
import com.example.Ece.agent.engine.TomatoSimulationEngine;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 指标矩阵报告：打印各档对照的完整数字，既是人工核对手段，也是参赛材料"应用成效"的取证脚本。
 * 断言只保留"必须成立"的关系，避免把报告测试写成脆弱测试。
 *
 * <p>其中 P4/P5/P6 是同一控制器只保留不同控制项的消融，用来检验
 * "比例控制留稳态偏差、积分项消偏、微分项抑制超调"。
 * 判据用逐步统计的**超温量**（只计正向偏差）而非逐日序列的平均偏差——
 * 后者取的是午夜采样，夜间低温会把数值整体抬高到 6℃ 量级，掩盖掉控制方式造成的差异。</p>
 */
class PerformanceMatrixReportTest {

    private final PerformanceEvaluationService service = new PerformanceEvaluationService(
            new TomatoSimulationEngine(), new TomatoDecisionPolicy(), new TomatoCropGrowthModel(),
            new SoilWaterNutrientModel(), new PestDiseaseEpidemicModel(), new ManagementEconomicsModel());

    @Test
    void printsStrategyMatrix() {
        EvaluationBatch batch = service.runBatch("matrix-report", 20260921L, 120);
        System.out.println("=== 各档对照指标矩阵（120 天，seed=20260921）===");
        System.out.printf("%-24s %10s %10s %10s %10s %10s %10s %10s%n",
                "策略", "果实干重", "坐果率", "产量kg", "水m3", "电kWh", "成本元", "利润元");
        for (EvaluationStrategy strategy : EvaluationStrategy.values()) {
            EvaluationOutcome o = batch.getOutcomes().get(strategy);
            System.out.printf("%-24s %10.2f %10.3f %10.1f %10.2f %10.2f %10.1f %10.1f%n",
                    strategy.name(), o.getWFruit(), o.getFruitSetRate(), o.getYieldKg(),
                    o.getWaterUsedM3(), o.getEnergyKWh(), o.getCostYuan(), o.getProfitYuan());
        }
        System.out.println("--- 风险与合规 ---");
        System.out.printf("%-24s %10s %10s %10s %10s %10s %10s%n",
                "策略", "高温min", "高湿min", "高VPD min", "病害压力", "严重度%", "冲突次数");
        for (EvaluationStrategy strategy : EvaluationStrategy.values()) {
            EvaluationOutcome o = batch.getOutcomes().get(strategy);
            System.out.printf("%-24s %10d %10d %10d %10.1f %10.2f %10d%n",
                    strategy.name(), o.getHighTemperatureMinutes(), o.getHighHumidityMinutes(),
                    o.getHighVpdMinutes(), o.getDiseasePressureIntegral(), o.getFinalSeverityTotal(),
                    o.getConstraintViolations());
        }
        System.out.println("--- 逐步平均超出设定值的量（只计正向，控制器能作用的方向；越小越好）---");
        System.out.printf("%-24s %16s %16s%n", "策略", "超温 ℃", "超湿 %RH");
        for (EvaluationStrategy strategy : EvaluationStrategy.values()) {
            EvaluationOutcome o = batch.getOutcomes().get(strategy);
            System.out.printf("%-24s %16.3f %16.3f%n", strategy.name(),
                    o.getMeanTemperatureExceedanceC(), o.getMeanHumidityExceedancePct());
        }

        EvaluationOutcome none = batch.getOutcomes().get(EvaluationStrategy.P0_NONE);
        EvaluationOutcome rule = batch.getOutcomes().get(EvaluationStrategy.P2_RULE_ENGINE);
        EvaluationOutcome agent = batch.getOutcomes().get(EvaluationStrategy.P3_AGENT);

        assertTrue(rule.getWaterUsedM3() > none.getWaterUsedM3(), "调控档应产生灌溉用水");
        assertTrue(rule.getDiseasePressureIntegral() <= none.getDiseasePressureIntegral() * 1.05,
                "调控档不应比放任档更容易发病");
        boolean distinguishable = Math.abs(agent.getWFruit() - rule.getWFruit()) > 1e-6
                || Math.abs(agent.getWaterUsedM3() - rule.getWaterUsedM3()) > 1e-6
                || Math.abs(agent.getEnergyKWh() - rule.getEnergyKWh()) > 1e-6
                || agent.getHighTemperatureMinutes() != rule.getHighTemperatureMinutes();
        assertTrue(distinguishable, "智能体档与规则档完全一致——沙盘推演没有产生任何差异，需检查择优逻辑");
    }

    /**
     * 连续控制消融：三档共用同一控制器与同一套设备，只差保留哪些控制项，
     * 因此这里的差异可以单独归因于控制项本身。
     *
     * <p>断言方向取自本模型实测：积分项确实降低了温度跟踪误差，微分项确实降低了控制量波动。
     * 若哪天模型参数变更使方向反转，测试应失败并促使人重新核对，而不是放宽断言。</p>
     */
    @Test
    void continuousControlAblationSeparatesTheThreeControlTerms() {
        EvaluationBatch batch = service.runBatch("ablation-report", 20260921L, 120);
        EvaluationOutcome rule = batch.getOutcomes().get(EvaluationStrategy.P2_RULE_ENGINE);
        EvaluationOutcome proportional = batch.getOutcomes().get(EvaluationStrategy.P4_PID_PROPORTIONAL);
        EvaluationOutcome integral = batch.getOutcomes().get(EvaluationStrategy.P5_PID_PI);
        EvaluationOutcome full = batch.getOutcomes().get(EvaluationStrategy.P6_PID_FULL);

        System.out.printf("%n=== 连续控制消融（120 天）==="
                        + "%n%-18s %12s %12s %12s %12s%n",
                "策略", "超温℃", "超湿%RH", "高温min", "果实干重");
        for (EvaluationOutcome o : new EvaluationOutcome[]{rule, proportional, integral, full}) {
            System.out.printf("%-18s %12.3f %12.3f %12d %12.2f%n",
                    o == rule ? "P2_RULE_ENGINE" : o == proportional ? "P4_PID_P" : o == integral ? "P5_PID_PI" : "P6_PID_FULL",
                    o.getMeanTemperatureExceedanceC(), o.getMeanHumidityExceedancePct(),
                    o.getHighTemperatureMinutes(), o.getWFruit());
        }

        // 三档必须两两可区分，否则消融没有意义（微分增益未接上时正是这个症状）。
        assertNotEquals(proportional.getWFruit(), integral.getWFruit(), 1e-9,
                "P 与 PI 结果完全相同：积分项未生效");
        assertNotEquals(integral.getWFruit(), full.getWFruit(), 1e-9,
                "PI 与 PID 结果完全相同：微分项未生效");
        // 积分项的作用：消除比例控制遗留的稳态偏差，直接体现为超温量下降。
        assertTrue(integral.getMeanTemperatureExceedanceC() < proportional.getMeanTemperatureExceedanceC(),
                "加积分后超温量应下降，实测 PI=" + integral.getMeanTemperatureExceedanceC()
                        + " P=" + proportional.getMeanTemperatureExceedanceC());
        // 微分项的作用：在积分基础上进一步压低超调，超温量不应反弹。
        assertTrue(full.getMeanTemperatureExceedanceC() <= integral.getMeanTemperatureExceedanceC(),
                "加微分后超温量不应上升，实测 PID=" + full.getMeanTemperatureExceedanceC()
                        + " PI=" + integral.getMeanTemperatureExceedanceC());
        // 连续调节档在最基本的合规约束上必须干净。
        for (EvaluationStrategy strategy : new EvaluationStrategy[]{
                EvaluationStrategy.P4_PID_PROPORTIONAL, EvaluationStrategy.P5_PID_PI,
                EvaluationStrategy.P6_PID_FULL}) {
            assertEquals(0, batch.getOutcomes().get(strategy).getConstraintViolations(),
                    strategy + " 出现设备互斥冲突");
        }
    }

    @Test
    void continuousControlIsReproducibleForTheSameSeed() {
        EvaluationBatch first = service.runBatch("repeat-a", 20260921L, 5);
        EvaluationBatch second = service.runBatch("repeat-a", 20260921L, 5);
        for (EvaluationStrategy strategy : EvaluationStrategy.values()) {
            EvaluationOutcome left = first.getOutcomes().get(strategy);
            EvaluationOutcome right = second.getOutcomes().get(strategy);
            assertEquals(left.getWFruit(), right.getWFruit(), 1e-9, strategy + " 产量不可复算");
            assertEquals(left.getHighTemperatureMinutes(), right.getHighTemperatureMinutes(),
                    strategy + " 高温暴露不可复算");
            assertEquals(left.getCostYuan(), right.getCostYuan(), 1e-9, strategy + " 成本不可复算");
            assertEquals(left.getWaterUsedM3(), right.getWaterUsedM3(), 1e-9, strategy + " 用水不可复算");
        }
    }
}
