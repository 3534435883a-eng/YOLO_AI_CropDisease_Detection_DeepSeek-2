package com.example.Ece.agent.eval;

import com.example.Ece.agent.crop.TomatoCropGrowthModel;
import com.example.Ece.agent.eco.ManagementEconomicsModel;
import com.example.Ece.agent.eco.PestDiseaseEpidemicModel;
import com.example.Ece.agent.eco.SoilWaterNutrientModel;
import com.example.Ece.agent.engine.TomatoDecisionPolicy;
import com.example.Ece.agent.engine.TomatoSimulationEngine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerformanceEvaluationServiceTest {

    private final PerformanceEvaluationService service = new PerformanceEvaluationService(
            new TomatoSimulationEngine(), new TomatoDecisionPolicy(), new TomatoCropGrowthModel(),
            new SoilWaterNutrientModel(), new PestDiseaseEpidemicModel(), new ManagementEconomicsModel());

    @Test
    void producesAllFourStrategies() {
        EvaluationBatch batch = service.runBatch("batch-a", 20260921L, 3);
        assertEquals(4, batch.getOutcomes().size());
        for (EvaluationStrategy strategy : EvaluationStrategy.values()) {
            assertTrue(batch.getOutcomes().containsKey(strategy), "缺少策略 " + strategy);
        }
    }

    @Test
    void isReproducibleForSameSeed() {
        EvaluationBatch first = service.runBatch("batch-b", 7L, 2);
        EvaluationBatch second = service.runBatch("batch-b", 7L, 2);
        for (EvaluationStrategy strategy : EvaluationStrategy.values()) {
            assertEquals(first.getOutcomes().get(strategy).getWFruit(),
                    second.getOutcomes().get(strategy).getWFruit(), 1e-9);
            assertEquals(first.getOutcomes().get(strategy).getHighTemperatureMinutes(),
                    second.getOutcomes().get(strategy).getHighTemperatureMinutes());
            assertEquals(first.getOutcomes().get(strategy).getCostYuan(),
                    second.getOutcomes().get(strategy).getCostYuan(), 1e-9);
        }
    }

    @Test
    void regulatedStrategiesOutperformNoControlOnRiskAndYield() {
        EvaluationBatch batch = service.runBatch("batch-c", 20260921L, 120);
        EvaluationOutcome none = batch.getOutcomes().get(EvaluationStrategy.P0_NONE);
        EvaluationOutcome rule = batch.getOutcomes().get(EvaluationStrategy.P2_RULE_ENGINE);
        EvaluationOutcome agent = batch.getOutcomes().get(EvaluationStrategy.P3_AGENT);

        assertTrue(rule.getHighTemperatureMinutes() <= none.getHighTemperatureMinutes(),
                "规则引擎应至少不劣于无调控的高温暴露时长");
        assertTrue(rule.getWFruit() > 0.0, "完整生长季后产量应大于 0");
        assertTrue(rule.getWFruit() >= none.getWFruit() * 0.95, "规则档产量不应显著低于无调控");
        assertTrue(agent.getWFruit() >= none.getWFruit() * 0.95, "智能体档产量不应显著低于无调控");
    }

    @Test
    void seriesCarryDailyProgression() {
        EvaluationBatch batch = service.runBatch("batch-d", 1L, 3);
        List<Map<String, Object>> series = batch.getOutcomes().get(EvaluationStrategy.P2_RULE_ENGINE).getSeries();
        assertEquals(3, series.size());
        Map<String, Object> first = series.get(0);
        assertTrue(first.containsKey("lai"));
        assertTrue(first.containsKey("wFruit"));
        assertTrue(first.containsKey("day"));
        double lastDayLai = ((Number) series.get(series.size() - 1).get("lai")).doubleValue();
        assertTrue(lastDayLai >= ((Number) first.get("lai")).doubleValue(), "叶面积指数不应倒退");
    }

    @Test
    void noConstraintViolationsInAnyStrategy() {
        EvaluationBatch batch = service.runBatch("batch-e", 5L, 10);
        for (EvaluationStrategy strategy : EvaluationStrategy.values()) {
            assertEquals(0, batch.getOutcomes().get(strategy).getConstraintViolations(),
                    strategy + " 出现设备互斥冲突");
            assertFalse(batch.getOutcomes().get(strategy).getSeries().isEmpty());
        }
    }
}
