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
        double expectedYieldKg = 300.0 * SoilParameters.BED_AREA_M2 / 1000.0
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

    /**
     * 分时电价的时段划分必须与出处一致。
     *
     * <p>本测试锁的是**时段边界**，不是价格数值——边界写错（如把 18:00 算进平段、
     * 或把低谷写成夜间而非午间）会让分时电价静默失效，而数值看上去仍然"合理"。
     * 出处：甘肃 甘发改价格〔2024〕424号。高峰 6-8 与 18-23；低谷 10-16；其余平段。</p>
     */
    @Test
    void energyPriceFollowsTimeOfUseWindows() {
        double peak = EconomicsParameters.ENERGY_PEAK_YUAN_PER_KWH;
        double flat = EconomicsParameters.ENERGY_YUAN_PER_KWH;
        double valley = EconomicsParameters.ENERGY_VALLEY_YUAN_PER_KWH;

        // 高峰：6:00-8:00（含 6、7）与 18:00-23:00（含 18~22，不含 23）
        assertEquals(peak, EconomicsParameters.energyPriceAt(6), 1e-9);
        assertEquals(peak, EconomicsParameters.energyPriceAt(7), 1e-9);
        assertEquals(peak, EconomicsParameters.energyPriceAt(18), 1e-9);
        assertEquals(peak, EconomicsParameters.energyPriceAt(22), 1e-9);

        // 低谷：10:00-16:00（含 10~15，不含 16）——**午间**低谷，不是夜间
        assertEquals(valley, EconomicsParameters.energyPriceAt(10), 1e-9);
        assertEquals(valley, EconomicsParameters.energyPriceAt(12), 1e-9);
        assertEquals(valley, EconomicsParameters.energyPriceAt(15), 1e-9);

        // 平段：23:00-6:00、8:00-10:00、16:00-18:00
        assertEquals(flat, EconomicsParameters.energyPriceAt(23), 1e-9);
        assertEquals(flat, EconomicsParameters.energyPriceAt(0), 1e-9);
        assertEquals(flat, EconomicsParameters.energyPriceAt(5), 1e-9);
        assertEquals(flat, EconomicsParameters.energyPriceAt(8), 1e-9);
        assertEquals(flat, EconomicsParameters.energyPriceAt(9), 1e-9);
        assertEquals(flat, EconomicsParameters.energyPriceAt(16), 1e-9);
        assertEquals(flat, EconomicsParameters.energyPriceAt(17), 1e-9);

        // 越界钟点按 24 取模，不得抛异常
        assertEquals(peak, EconomicsParameters.energyPriceAt(30), 1e-9);
        assertEquals(peak, EconomicsParameters.energyPriceAt(-6), 1e-9);

        assertTrue(valley < flat && flat < peak, "峰谷必须单调：谷 < 平 < 峰");
    }

    /** 同一步用量、不同钟点，电费必须不同——否则分时电价等于没接上。 */
    @Test
    void sameUsageCostsDifferentlyAtPeakAndValley() {
        ResourceUsage usage = new ResourceUsage(0.0, 10.0, 0.0, 0.0, 0.0, 0.0);
        EconomicsState atPeak = model.advance(model.initial(), usage, cropWithFruit(100.0), null, 15, 19);
        EconomicsState atValley = model.advance(model.initial(), usage, cropWithFruit(100.0), null, 15, 12);
        assertTrue(atPeak.getCostYuan() > atValley.getCostYuan(),
                "高峰时段同量用电的成本应更高：峰 " + atPeak.getCostYuan() + " vs 谷 " + atValley.getCostYuan());
    }

    /**
     * 超定额累进水价的档位边界必须与出处一致。
     *
     * <p>锁的是**档位边界**：写错边界（如把"超 50%"写成"超 100%"）会让累进静默失效，
     * 数值看上去仍然合理。出处：山东口径——超定额 50% 以内 1.5 倍、以上 2 倍；
     * 定额取 DB37/T 1849—2026 的秋冬茬 129 m³/667m²。</p>
     */
    @Test
    void waterPriceIsTieredByCumulativeUsage() {
        double base = EconomicsParameters.WATER_YUAN_PER_M3;
        double quota = EconomicsParameters.waterQuotaM3();
        assertTrue(quota > 0, "定额必须为正：" + quota);

        assertEquals(base, EconomicsParameters.waterPriceAt(0.0), 1e-9);
        assertEquals(base, EconomicsParameters.waterPriceAt(quota), 1e-9, "恰好等于定额仍在第一档");
        assertEquals(base * 1.5, EconomicsParameters.waterPriceAt(quota * 1.2), 1e-9);
        assertEquals(base * 1.5, EconomicsParameters.waterPriceAt(quota * 1.5), 1e-9, "恰好 1.5 倍仍在第二档");
        assertEquals(base * 2.0, EconomicsParameters.waterPriceAt(quota * 1.6), 1e-9);
        assertEquals(base * 2.0, EconomicsParameters.waterPriceAt(quota * 10.0), 1e-9);
    }

    /**
     * 超定额后**同样体积**的边际水费必须高于定额内。
     *
     * <p>做法是比两个位置上的**同一步**用水：季初一步、超定额后一步。
     * 两步的固定成本相同，相减即抵消，剩下的差异只可能来自水价档位——
     * 这正是要验的东西。</p>
     *
     * <p>（不要拿"一步的累计成本差"当边际水费：那里面还含该步的固定成本，
     * 实测会得到 6.208 而不是 6.0——第一版就是这么写错的。）</p>
     */
    @Test
    void marginalWaterCostRisesBeyondQuota() {
        ResourceUsage fiveCubicMeters = new ResourceUsage(5.0, 0.0, 0.0, 0.0, 0.0, 0.0);

        double startCost = model.initial().getCostYuan();
        double earlyStep = model.advance(model.initial(), fiveCubicMeters, cropWithFruit(100.0), null, 15)
                .getCostYuan() - startCost;

        EconomicsState state = model.initial();
        int guard = 0;
        while (state.getWaterUsedM3() <= EconomicsParameters.waterQuotaM3() && guard++ < 100) {
            state = model.advance(state, fiveCubicMeters, cropWithFruit(100.0), null, 15);
        }
        assertTrue(state.getWaterUsedM3() > EconomicsParameters.waterQuotaM3(), "应已超过定额");
        // 该循环每步 5 m³、定额 27.6 m³，退出时累计 30 m³，落在第二档（< 定额×1.5 = 41.4）
        assertTrue(state.getWaterUsedM3() < EconomicsParameters.waterQuotaM3() * 1.5,
                "测试前提：退出时应落在第二档，实测 " + state.getWaterUsedM3());
        double lateStep = model.advance(state, fiveCubicMeters, cropWithFruit(100.0), null, 15)
                .getCostYuan() - state.getCostYuan();

        double expectedGap = 5.0 * EconomicsParameters.WATER_YUAN_PER_M3 * 0.5; // 1.5 倍与基准的差
        assertEquals(expectedGap, lateStep - earlyStep, 1e-6,
                "超定额 50% 以内应比基准价多付 50%：实测早 " + earlyStep + " 晚 " + lateStep);
    }
}
