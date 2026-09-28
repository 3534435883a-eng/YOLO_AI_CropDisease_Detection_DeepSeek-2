package com.example.Ece.agent.plan;

import com.example.Ece.agent.crop.TomatoCropGrowthModel;
import com.example.Ece.agent.eco.ManagementEconomicsModel;
import com.example.Ece.agent.eco.PestDiseaseEpidemicModel;
import com.example.Ece.agent.eco.SoilWaterNutrientModel;
import com.example.Ece.agent.engine.TomatoDecisionPolicy;
import com.example.Ece.agent.engine.TomatoSimulationEngine;
import com.example.Ece.agent.eval.PerformanceEvaluationService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 参考基线的边界测试。
 *
 * <p>这里最重要的一条来自**真实推演的实测发现**（2026-09-28）：60 天窗口下利润算出来是
 * -1447.70 元。数字本身没错，但它的含义是"窗口太短"而不是"经营亏损"，
 * 而模型看到的只是一个负数。</p>
 */
class AgriPlanBaselineTest {

    private final AgriPlanBaseline baseline = new AgriPlanBaseline(
            new PerformanceEvaluationService(
                    new TomatoSimulationEngine(), new TomatoDecisionPolicy(), new TomatoCropGrowthModel(),
                    new SoilWaterNutrientModel(), new PestDiseaseEpidemicModel(),
                    new ManagementEconomicsModel()));

    @Test
    void promptsAlwaysStateThatUserInputIsNotUsed() {
        AgriPlanBaseline.Baseline result = baseline.compute(20260928L, 60);

        assertTrue(result.isAvailable(), result.getUnavailableReason());
        // 这句话是这份数值唯一没说谎的前提，必须排在数值之前。
        assertTrue(result.getPromptBlock().startsWith("以下数值来自**固定标准情景**"),
                "提示词里必须最先说明它不采用用户输入");
        assertTrue(result.getPromptBlock().contains("不采用你提供的农情输入"));
    }

    @Test
    void shortWindowNegativeProfitIsExplainedRatherThanLeftAsASignal() {
        AgriPlanBaseline.Baseline result = baseline.compute(20260928L, 60);

        assertTrue(result.getProfitYuan() < 0, "60 天窗口下利润本应为负（实测 -1447.70）");
        assertNotNull(result.getWindowNote(), "利润为负时必须给出解释，否则会被读成经营亏损");
        assertTrue(result.getWindowNote().contains("不代表经营亏损"));
        // 解释要同时进提示词——模型看到的也是这个负数。
        assertTrue(result.getPromptBlock().contains("不代表经营亏损"));
    }

    @Test
    void fullSeasonWindowNeedsNoSuchExplanation() {
        AgriPlanBaseline.Baseline result = baseline.compute(20260928L, 120);

        assertTrue(result.isAvailable(), result.getUnavailableReason());
        assertTrue(result.getProfitYuan() > 0, "完整生长季应能覆盖成本");
        assertNull(result.getWindowNote(), "利润为正时不该出现窗口不足的提示");
    }

    @Test
    void cachesBySeedAndDaysBecauseEachBatchIsTensOfThousandsOfSteps() {
        AgriPlanBaseline.Baseline first = baseline.compute(20260928L, 60);
        long startedAt = System.currentTimeMillis();
        AgriPlanBaseline.Baseline second = baseline.compute(20260928L, 60);
        long elapsed = System.currentTimeMillis() - startedAt;

        assertEquals(first.getBatchId(), second.getBatchId());
        assertEquals(first.getProfitYuan(), second.getProfitYuan(), 1e-9);
        // 命中缓存时不该再跑一遍 7 档 × 60 天 × 96 步。
        assertTrue(elapsed < 200, "第二次取同种子的基线应命中缓存，实测耗时 " + elapsed + "ms");
    }
}
