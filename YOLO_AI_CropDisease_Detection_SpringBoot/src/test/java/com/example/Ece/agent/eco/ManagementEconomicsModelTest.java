package com.example.Ece.agent.eco;

import com.example.Ece.agent.crop.CropStage;
import com.example.Ece.agent.crop.TomatoCropGrowthModel;
import com.example.Ece.agent.crop.TomatoCropState;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagementEconomicsModelTest {

    private final ManagementEconomicsModel model = new ManagementEconomicsModel();

    private TomatoCropState cropWithFruit(double wFruit) {
        return new TomatoCropState(700.0, 2.4, 120.0, 60.0, 40.0, 20.0, wFruit, 120.0 + wFruit,
                0.6, 12, wFruit / Math.max(1.0, 12.0), 1.0, 1.0, 1.0, CropStage.FRUIT_GROWTH, false);
    }

    private DiseaseState diseaseWithDamage(double damageFactor) {
        return new DiseaseState(new EnumMap<DiseaseKind, Double>(DiseaseKind.class),
                new EnumMap<DiseaseKind, Double>(DiseaseKind.class),
                new EnumMap<DiseaseKind, Double>(DiseaseKind.class), 0, damageFactor, 5.0);
    }

    @Test
    void costEqualsSumOfUsageTimesUnitPricePlusFixedCost() {
        ResourceUsage usage = new ResourceUsage(2.0, 10.0, 1.0, 5.0, 0.2, 3.0);
        EconomicsState state = model.advance(model.initial(), usage, null, null, 1440);
        double expected = 2.0 * EconomicsParameters.WATER_YUAN_PER_M3
                + 10.0 * EconomicsParameters.ENERGY_YUAN_PER_KWH
                + 1.0 * EconomicsParameters.CO2_YUAN_PER_KG
                + 5.0 * EconomicsParameters.FERTILIZER_YUAN_PER_KG
                + 0.2 * EconomicsParameters.PESTICIDE_YUAN_PER_KG
                + 3.0 * EconomicsParameters.LABOR_YUAN_PER_HOUR
                + EconomicsParameters.FIXED_COST_YUAN_PER_DAY;
        assertEquals(expected, state.getCostYuan(), 1e-6);
        assertEquals(2.0, state.getWaterUsedM3(), 1e-9);
        assertEquals(0.2, state.getPesticideUsedKg(), 1e-9);
    }

    @Test
    void revenueSplitsFirstAndSecondGradeAndShrinksWithDiseaseDamage() {
        TomatoCropState crop = cropWithFruit(300.0);
        EconomicsState healthy = model.advance(model.initial(), ResourceUsage.none(), crop,
                diseaseWithDamage(1.0), 1440);
        double expectedYieldKg = 300.0 * EconomicsParameters.GREENHOUSE_AREA_M2 / 1000.0
                / EconomicsParameters.FRUIT_DRY_MATTER_FRACTION;
        assertEquals(expectedYieldKg, healthy.getYieldKg(), 1e-6);
        double gradeA = expectedYieldKg * EconomicsParameters.GRADE_A_RATIO;
        double gradeB = expectedYieldKg - gradeA;
        double expectedRevenue = gradeA * EconomicsParameters.GRADE_A_PRICE_YUAN_PER_KG
                + gradeB * EconomicsParameters.GRADE_B_PRICE_YUAN_PER_KG;
        assertEquals(expectedRevenue, healthy.getRevenueYuan(), 1e-6);

        EconomicsState damaged = model.advance(model.initial(), ResourceUsage.none(), crop,
                diseaseWithDamage(0.5), 1440);
        assertEquals(expectedYieldKg, damaged.getYieldKg(), 1e-6);
        assertEquals(expectedYieldKg * 0.5, damaged.getMarketableYieldKg(), 1e-6);
        assertTrue(damaged.getRevenueYuan() < healthy.getRevenueYuan());
    }

    @Test
    void profitIsRevenueMinusCostAndCostAccumulates() {
        ResourceUsage usage = new ResourceUsage(1.0, 4.0, 0.0, 2.0, 0.0, 1.0);
        EconomicsState first = model.advance(model.initial(), usage, cropWithFruit(100.0), null, 720);
        assertEquals(first.getRevenueYuan() - first.getCostYuan(), first.getProfitYuan(), 1e-6);

        EconomicsState second = model.advance(first, usage, cropWithFruit(100.0), null, 720);
        assertTrue(second.getCostYuan() > first.getCostYuan(), "成本必须逐日累加");
        assertEquals(2.0, second.getWaterUsedM3(), 1e-9);
    }

    @Test
    void perYieldMetricsAreSafeWhenYieldIsZero() {
        TomatoCropState seedling = new TomatoCropGrowthModel().initial();
        EconomicsState state = model.advance(model.initial(), new ResourceUsage(3.0, 6.0, 0.0, 0.0, 0.0, 0.5),
                seedling, null, 1440);
        assertEquals(0.0, state.getYieldKg(), 1e-9);
        assertEquals(0.0, state.getWaterPerYield(), 1e-9);
        assertEquals(0.0, state.getEnergyPerYield(), 1e-9);
        assertFalse(Double.isNaN(state.getWaterPerYield()));
        assertFalse(Double.isInfinite(state.getEnergyPerYield()));
        assertTrue(state.getProfitYuan() < 0.0, "尚未产出时应为亏损");
    }
}
