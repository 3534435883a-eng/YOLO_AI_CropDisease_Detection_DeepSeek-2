package com.example.Ece.agent.eco;

import com.example.Ece.agent.crop.TomatoCropGrowthModel;
import com.example.Ece.agent.crop.TomatoCropState;
import com.example.Ece.agent.engine.TomatoSimulationEngine;
import com.example.Ece.agent.model.SimulationState;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SoilWaterNutrientModel} 的确定性场景测试。
 *
 * <p>每个测试各自构造环境与初始状态，不共享任何可变状态。</p>
 */
class SoilWaterNutrientModelTest {

    private static final int STEP_MINUTES = 15;
    private static final int SHORT_RUN_STEPS = 96;
    private static final int DEPLETION_RUN_STEPS = 480;
    private static final double FERTILIZER_DOSE_KG_PER_HA = 5.0;

    private final SoilWaterNutrientModel model = new SoilWaterNutrientModel();
    private final TomatoSimulationEngine engine = new TomatoSimulationEngine();
    private final TomatoCropGrowthModel cropModel = new TomatoCropGrowthModel();

    /** 用真实模拟引擎构造环境状态。 */
    private SimulationState environment(double temperatureC, double airHumidityPct, double lightPpfd) {
        return engine.evaluate(LocalDateTime.of(2026, 5, 12, 12, 0), temperatureC, airHumidityPct,
                60.0, 800.0, lightPpfd, 6.2);
    }

    /** 从 initial() 出发推进固定步数；作物固定为苗期初始状态。 */
    private SoilState simulate(SimulationState environment, int steps, boolean irrigationOn,
                              double fertilizerKgPerHa) {
        SoilState state = model.initial();
        TomatoCropState crop = cropModel.initial();
        for (int i = 0; i < steps; i++) {
            state = model.advance(state, environment, crop, STEP_MINUTES, irrigationOn, fertilizerKgPerHa);
        }
        return state;
    }

    @Test
    void soilMoistureDropsWithoutIrrigation() {
        SoilState initial = model.initial();
        SoilState dry = simulate(environment(25.0, 60.0, 520.0), SHORT_RUN_STEPS, false, 0.0);
        assertTrue(dry.getSoilMoisturePct() < initial.getSoilMoisturePct(),
                "不灌溉时土壤含水率应因蒸散而下降");
        assertEquals(0.0, dry.getIrrigationMmTotal(), 1e-9, "不灌溉时累计灌溉量应为 0");
    }

    @Test
    void irrigationRaisesSoilMoisture() {
        SimulationState environment = environment(25.0, 60.0, 520.0);
        SoilState dry = simulate(environment, SHORT_RUN_STEPS, false, 0.0);
        SoilState wet = simulate(environment, SHORT_RUN_STEPS, true, 0.0);
        assertTrue(wet.getSoilMoisturePct() > dry.getSoilMoisturePct(), "灌溉应提高土壤含水率");
        assertTrue(wet.getIrrigationMmTotal() > dry.getIrrigationMmTotal(), "灌溉应累计灌溉量");
    }

    @Test
    void fertilizerRaisesNitrogenAndEc() {
        SimulationState environment = environment(25.0, 60.0, 520.0);
        SoilState base = simulate(environment, SHORT_RUN_STEPS, false, 0.0);
        SoilState fed = simulate(environment, SHORT_RUN_STEPS, false, FERTILIZER_DOSE_KG_PER_HA);
        assertTrue(fed.getNitrogenKgPerHa() > base.getNitrogenKgPerHa(), "施肥应提高土壤速效氮");
        assertTrue(fed.getEcDsPerM() > base.getEcDsPerM(), "施肥应提高土壤 EC");
    }

    @Test
    void nutrientFactorStaysInRangeAndFallsWhenDepleted() {
        SoilState initial = model.initial();
        SoilState depleted = simulate(environment(26.0, 55.0, 620.0), DEPLETION_RUN_STEPS, false, 0.0);
        double nutrientFactor = depleted.getNutrientFactor();
        assertTrue(nutrientFactor >= 0.3, "养分因子不得低于下限 0.3，实际 " + nutrientFactor);
        assertTrue(nutrientFactor <= 1.0, "养分因子不得超过 1.0，实际 " + nutrientFactor);
        assertTrue(nutrientFactor <= initial.getNutrientFactor() + 1e-9, "无施肥消耗后养分因子不应上升");
        assertTrue(depleted.getNitrogenKgPerHa() <= initial.getNitrogenKgPerHa(), "作物带走后土壤氮不应增加");
    }

    @Test
    void isDeterministic() {
        SimulationState environment = environment(24.0, 70.0, 500.0);
        SoilState first = simulate(environment, DEPLETION_RUN_STEPS, true, 3.0);
        SoilState second = simulate(environment, DEPLETION_RUN_STEPS, true, 3.0);
        assertEquals(first.getSoilMoisturePct(), second.getSoilMoisturePct(), 1e-9);
        assertEquals(first.getEcDsPerM(), second.getEcDsPerM(), 1e-9);
        assertEquals(first.getSoilPh(), second.getSoilPh(), 1e-9);
        assertEquals(first.getNitrogenKgPerHa(), second.getNitrogenKgPerHa(), 1e-9);
        assertEquals(first.getPhosphorusKgPerHa(), second.getPhosphorusKgPerHa(), 1e-9);
        assertEquals(first.getPotassiumKgPerHa(), second.getPotassiumKgPerHa(), 1e-9);
        assertEquals(first.getLeachedNitrogenKgPerHa(), second.getLeachedNitrogenKgPerHa(), 1e-9);
        assertEquals(first.getIrrigationMmTotal(), second.getIrrigationMmTotal(), 1e-9);
        assertEquals(first.getNutrientFactor(), second.getNutrientFactor(), 1e-9);
    }

    @Test
    void zeroStepAdvanceIsIdentityAndInitialNutrientFactorIsConsistent() {
        SoilWaterNutrientModel soil = new SoilWaterNutrientModel();
        SoilState base = soil.initial();
        SoilState same = soil.advance(base, null, null, 0, false, 0.0);
        assertEquals(base.getSoilMoisturePct(), same.getSoilMoisturePct(), 1e-9);
        assertEquals(base.getSoilPh(), same.getSoilPh(), 1e-9);
        assertEquals(base.getEcDsPerM(), same.getEcDsPerM(), 1e-9);
        assertEquals(base.getNitrogenKgPerHa(), same.getNitrogenKgPerHa(), 1e-9);
        assertEquals(base.getIrrigationMmTotal(), same.getIrrigationMmTotal(), 1e-9);
        assertEquals(base.getNutrientFactor(), same.getNutrientFactor(), 1e-9,
                "零步推进不得改变养分因子，否则 initial() 与公式自相矛盾");
        assertEquals(1.0, base.getNutrientFactor(), 1e-9, "初始土壤应视为养分充足");
    }
}
