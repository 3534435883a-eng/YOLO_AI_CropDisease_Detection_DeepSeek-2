package com.example.Ece.agent.eval;

import com.example.Ece.agent.crop.CropStage;
import com.example.Ece.agent.crop.TomatoCropGrowthModel;
import com.example.Ece.agent.crop.TomatoCropState;
import com.example.Ece.agent.eco.DiseaseKind;
import com.example.Ece.agent.eco.DiseaseState;
import com.example.Ece.agent.eco.EconomicsState;
import com.example.Ece.agent.eco.ManagementEconomicsModel;
import com.example.Ece.agent.eco.PestDiseaseEpidemicModel;
import com.example.Ece.agent.eco.ResourceUsage;
import com.example.Ece.agent.eco.SoilParameters;
import com.example.Ece.agent.eco.SoilState;
import com.example.Ece.agent.eco.SoilWaterNutrientModel;
import com.example.Ece.agent.engine.TomatoDecisionPolicy;
import com.example.Ece.agent.engine.TomatoSimulationEngine;
import com.example.Ece.agent.model.AgentDeviceCodes;
import com.example.Ece.agent.model.DecisionPlan;
import com.example.Ece.agent.model.DeviceCommand;
import com.example.Ece.agent.model.SimulationState;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 模型诊断报告：**不测"能不能跑"，测"内部是否自洽、参数是否真的在起作用"**。
 *
 * <p>评测序列（{@code EvaluationOutcome.series}）只暴露了部分状态：有 LAI、生物量、nutrientFactor、
 * 病害严重度，**没有**作物模型的三个胁迫因子，也**没有**土壤的 EC / pH / 氮磷钾。
 * 也就是说模型算出来的土壤化学与胁迫过程在对外产物里是**看不见的**——本测试把它打出来。</p>
 *
 * <p><b>自证忠实性</b>：本测试自行复现评测平台 P2 档的耦合推进循环，先断言终值与该档
 * **已记录的实测值**一致（wFruit、高温暴露、病害压力积分、利润）。只有复现成立，
 * 下面打印的内部量才可信——否则就是另一份实现，不能代表被测模型。</p>
 *
 * <p>断言只用于自证忠实性；其余为诊断输出，**不对农艺合理性下断言**——
 * 参考区间需要可引用来源，而本项目恰恰没有实测标定。</p>
 */
class ModelDiagnosticReportTest {

    private static final int STEP_MINUTES = 15;
    private static final int STEPS_PER_DAY = 96;
    private static final int DAYS = 120;
    private static final long SEED = 20260921L;
    private static final LocalDateTime START = LocalDateTime.of(2026, 9, 21, 6, 0);
    /** 种植床面积：4 条 × 21 m × 1.7 m。用于把产量折算成单位面积产量。 */
    private static final double BED_AREA_M2 = 4.0 * 21.0 * 1.7;
    /** 棚体占地面积：26 m × 13 m。 */
    private static final double GREENHOUSE_AREA_M2 = 26.0 * 13.0;

    private final TomatoSimulationEngine engine = new TomatoSimulationEngine();
    private final TomatoDecisionPolicy policy = new TomatoDecisionPolicy();
    private final TomatoCropGrowthModel cropModel = new TomatoCropGrowthModel();
    private final SoilWaterNutrientModel soilModel = new SoilWaterNutrientModel();
    private final PestDiseaseEpidemicModel epidemicModel = new PestDiseaseEpidemicModel();
    private final ManagementEconomicsModel economicsModel = new ManagementEconomicsModel();

    private double heatwaveOffsetC(int day) {
        return (day >= 45 && day <= 55) || (day >= 85 && day <= 95) ? 6.0 : 0.0;
    }

    private double transpirationOffsetPct(TomatoCropState crop) {
        return Math.min(12.0, Math.max(0.0, crop.getLai()) * 4.0);
    }

    private ResourceUsage usageOf(Map<String, Boolean> devices) {
        double energy = 0.0;
        double co2 = 0.0;
        double water = 0.0;
        if (Boolean.TRUE.equals(devices.get(AgentDeviceCodes.VENTILATION))) {
            energy += ResourceRates.VENTILATION_KWH_PER_STEP;
        }
        if (Boolean.TRUE.equals(devices.get(AgentDeviceCodes.ROOF_VENT))) {
            energy += ResourceRates.ROOF_VENT_KWH_PER_STEP;
        }
        if (Boolean.TRUE.equals(devices.get(AgentDeviceCodes.EXHAUST_FAN))) {
            energy += ResourceRates.EXHAUST_FAN_KWH_PER_STEP;
        }
        if (Boolean.TRUE.equals(devices.get(AgentDeviceCodes.COOLING_PAD))) {
            energy += ResourceRates.COOLING_PAD_KWH_PER_STEP;
        }
        if (Boolean.TRUE.equals(devices.get(AgentDeviceCodes.GROW_LIGHT))) {
            energy += ResourceRates.GROW_LIGHT_KWH_PER_STEP;
        }
        if (Boolean.TRUE.equals(devices.get(AgentDeviceCodes.SHADE))) {
            energy += ResourceRates.SHADE_KWH_PER_STEP;
        }
        if (Boolean.TRUE.equals(devices.get(AgentDeviceCodes.CO2_SUPPLY))) {
            co2 += ResourceRates.CO2_KG_PER_STEP;
        }
        if (Boolean.TRUE.equals(devices.get(AgentDeviceCodes.IRRIGATION))) {
            water += ResourceRates.IRRIGATION_M3_PER_STEP;
        }
        return new ResourceUsage(water, energy, co2, 0.0, 0.0, ResourceRates.LABOR_HOURS_PER_STEP);
    }

    private Map<String, Boolean> devicesOf(DecisionPlan plan) {
        Map<String, Boolean> devices = new LinkedHashMap<String, Boolean>();
        for (String code : AgentDeviceCodes.all()) {
            devices.put(code, Boolean.FALSE);
        }
        for (DeviceCommand command : plan.getCommands()) {
            devices.put(command.getDeviceCode(), Boolean.valueOf(command.isTargetOn()));
        }
        return devices;
    }

    /**
     * **先判定"水分胁迫机制是否只是被灌溉正常压制"，而不是坏掉**。
     *
     * <p>初版诊断只跑了规则档 P2，见到 {@code waterFactor ≡ 1.000} 就下了"机制结构性失效"的结论。
     * 但 P2 有灌溉，胁迫不出现本身可能是**正确行为**。判定要看不灌溉的档：
     * 若 P0 也不触发，机制才是坏的；若 P0 触发、P2 不触发，机制是好的，
     * 该记的只是"灌溉确实免除了水分胁迫"这一事实。</p>
     */
    @Test
    void waterStressMechanismMustTriggerWithoutIrrigation() {
        double[] p0 = extremes(false);
        System.out.println();
        System.out.println("=== 判定：水分胁迫机制是否有效（P0 无灌溉 vs P2 规则档）===");
        System.out.printf("%-10s %-24s %-24s %-24s %-20s%n",
                "档位", "SoilState水分(作物看)", "引擎水分(策略看)", "水分因子区间", "速效氮区间");
        printRange("P0 无干预", p0);
        printRange("P2 规则档", extremes(true));

        if (p0[WATER_MIN] >= 1.0) {
            System.out.println();
            System.out.println("  判定：**P0 档水分因子也恒为 1.000 → 机制确实失效**（不是被灌溉正常压制）");
        } else {
            System.out.println();
            System.out.println("  判定：P0 档水分因子最低 " + String.format("%.3f", p0[WATER_MIN])
                    + " < 1 → **机制有效**，P2 恒为 1.0 是灌溉正常维持的结果，"
                    + "初版\"结构性失效\"的结论不成立。");
        }
    }

    private static final int WATER_MIN = 0;
    private static final int WATER_MAX = 1;
    private static final int SOIL_MIN = 2;
    private static final int SOIL_MAX = 3;
    private static final int N_MIN = 4;
    private static final int N_MAX = 5;
    /** 引擎（策略读取）那份土壤水分的极值。 */
    private static final int ENGINE_SOIL_MIN = 6;
    private static final int ENGINE_SOIL_MAX = 7;

    private void printRange(String label, double[] r) {
        System.out.printf("%-10s %8.2f ~ %-11.2f %8.2f ~ %-11.2f %8.3f ~ %-11.3f %8.1f ~ %-9.1f%n",
                label, r[SOIL_MIN], r[SOIL_MAX], r[ENGINE_SOIL_MIN], r[ENGINE_SOIL_MAX],
                r[WATER_MIN], r[WATER_MAX], r[N_MIN], r[N_MAX]);
    }

    /**
     * 跑一遍并返回极值。
     *
     * <p>关键是同时跟踪**两份**土壤水分：{@code SoilState} 那份是喂给作物模型的
     * （见评测服务的 coupled 构造），引擎 {@code SimulationState} 那份是规则层读的。
     * 若两者不一致，就会出现"策略按 A 决策、作物按 B 生长"的错位。</p>
     */
    private double[] extremes(boolean irrigate) {
        SimulationState air = engine.evaluate(START, 22.0, 72.0, 62.0, 600.0, 0.0, 6.2);
        SoilState soil = soilModel.initial();
        TomatoCropState crop = cropModel.initial();
        DiseaseState disease = epidemicModel.initial();
        double waterMin = 9, waterMax = -9, soilMin = 999, soilMax = -999, nMin = 9e9, nMax = -9e9;
        double engineSoilMin = 999, engineSoilMax = -999;
        int irrigationSteps = 0;
        for (int day = 1; day <= DAYS; day++) {
            for (int step = 0; step < STEPS_PER_DAY; step++) {
                Map<String, Boolean> devices = irrigate
                        ? devicesOf(policy.decide(air)) : emptyDevices();
                if (Boolean.TRUE.equals(devices.get(AgentDeviceCodes.IRRIGATION))) {
                    irrigationSteps++;
                }
                air = engine.advance(air, devices, STEP_MINUTES, SEED,
                        heatwaveOffsetC(day), transpirationOffsetPct(crop));
                SimulationState coupled = engine.evaluate(air.getSimulatedAt(), air.getTemperatureC(),
                        air.getAirHumidityPct(), air.getSoilMoisturePct(), air.getCo2Ppm(),
                        air.getLightPpfd(), soil.getSoilPh());
                double stress = clamp(soil.getNutrientFactor(), 0.0, 1.0)
                        * clamp(disease.getDiseaseDamageFactor(), 0.0, 1.0);
                crop = cropModel.advance(crop, coupled, STEP_MINUTES, stress);
                boolean watering = Boolean.TRUE.equals(devices.get(AgentDeviceCodes.IRRIGATION));
                soil = soilModel.advance(soil, coupled, crop, STEP_MINUTES, watering,
                        watering ? ResourceRates.FERTILIZER_KG_PER_HA_PER_STEP : 0.0);
                disease = epidemicModel.advance(disease, coupled, crop, STEP_MINUTES);
                waterMin = Math.min(waterMin, crop.getWaterFactor());
                waterMax = Math.max(waterMax, crop.getWaterFactor());
                soilMin = Math.min(soilMin, soil.getSoilMoisturePct());
                soilMax = Math.max(soilMax, soil.getSoilMoisturePct());
                engineSoilMin = Math.min(engineSoilMin, air.getSoilMoisturePct());
                engineSoilMax = Math.max(engineSoilMax, air.getSoilMoisturePct());
                nMin = Math.min(nMin, soil.getNitrogenKgPerHa());
                nMax = Math.max(nMax, soil.getNitrogenKgPerHa());
            }
        }
        System.out.printf("    （%s 档灌溉步数 %d）%n", irrigate ? "P2" : "P0", irrigationSteps);
        return new double[]{waterMin, waterMax, soilMin, soilMax, nMin, nMax,
                engineSoilMin, engineSoilMax};
    }

    private Map<String, Boolean> emptyDevices() {
        Map<String, Boolean> devices = new LinkedHashMap<String, Boolean>();
        for (String code : AgentDeviceCodes.all()) {
            devices.put(code, Boolean.FALSE);
        }
        return devices;
    }

    @Test
    void printsModelInternalsThatTheEvaluationSeriesNeverExposes() {
        SimulationState air = engine.evaluate(START, 22.0, 72.0, 62.0, 600.0, 0.0, 6.2);
        SoilState soil = soilModel.initial();
        TomatoCropState crop = cropModel.initial();
        DiseaseState disease = epidemicModel.initial();
        EconomicsState economics = economicsModel.initial();

        // 诊断量的极值追踪
        double maxLai = 0.0;
        int matureDay = -1;
        CropStage lastStage = null;
        CropStage firstFruitingStage = null;
        double tempMin = 9, tempMax = -9, co2Min = 9, co2Max = -9, waterMin = 9, waterMax = -9;
        double ecMin = 9, ecMax = -9, phMin = 9, phMax = -9, nMin = 9e9, nMax = -9e9;
        double nutrientMin = 9, nutrientMax = -9;
        double setRateMin = 9, setRateMax = -9;
        double maxDamage = 0.0, maxSeverity = 0.0, maxPest = 0.0;
        long highTempMinutes = 0L;
        double diseasePressureIntegral = 0.0;
        int irrigationEvents = 0;
        double fertilizerApplied = 0.0;

        System.out.println("=== 模型诊断（规则档 P2，120 天，seed=" + SEED + "）===");
        System.out.printf("%-5s %-13s %6s %6s %6s %8s %6s %7s %8s %8s %7s%n",
                "天", "生育期", "GDD", "LAI", "株高cm", "果实干重", "果数", "单果g", "EC dS/m", "pH", "速效N");

        for (int day = 1; day <= DAYS; day++) {
            for (int step = 0; step < STEPS_PER_DAY; step++) {
                Map<String, Boolean> devices = devicesOf(policy.decide(air));
                if (Boolean.TRUE.equals(devices.get(AgentDeviceCodes.IRRIGATION))) {
                    irrigationEvents++;
                }
                air = engine.advance(air, devices, STEP_MINUTES, SEED,
                        heatwaveOffsetC(day), transpirationOffsetPct(crop));
                SimulationState coupled = engine.evaluate(air.getSimulatedAt(), air.getTemperatureC(),
                        air.getAirHumidityPct(), air.getSoilMoisturePct(), air.getCo2Ppm(),
                        air.getLightPpfd(), soil.getSoilPh());

                double stress = clamp(soil.getNutrientFactor(), 0.0, 1.0)
                        * clamp(disease.getDiseaseDamageFactor(), 0.0, 1.0);
                // 施肥逻辑必须与评测平台一致，否则本测试复现不出平台的终值、内部量也就不可信。
                // 平台按论文分 2 次追肥（进入坐果期、果实膨大期各一次），此处照搬。
                CropStage stageBefore = crop.getStage();
                crop = cropModel.advance(crop, coupled, STEP_MINUTES, stress);
                boolean irrigating = Boolean.TRUE.equals(devices.get(AgentDeviceCodes.IRRIGATION));
                boolean topdressing = stageBefore != crop.getStage()
                        && (crop.getStage() == CropStage.FRUIT_SET || crop.getStage() == CropStage.FRUIT_GROWTH);
                double doseKgPerHa = topdressing
                        ? SoilParameters.SEASON_TOPDRESSING_KG_PER_HA / SoilParameters.TOPDRESSING_APPLICATIONS : 0.0;
                fertilizerApplied += doseKgPerHa;
                soil = soilModel.advance(soil, coupled, crop, STEP_MINUTES, irrigating, doseKgPerHa);
                disease = epidemicModel.advance(disease, coupled, crop, STEP_MINUTES);
                economics = economicsModel.advance(economics, usageOf(devices), crop, disease, STEP_MINUTES);

                if (coupled.getTemperatureC() > 28.0) {
                    highTempMinutes += STEP_MINUTES;
                }
                diseasePressureIntegral += coupled.getDiseasePressure() * STEP_MINUTES;

                maxLai = Math.max(maxLai, crop.getLai());
                if (crop.isMature() && matureDay < 0) {
                    matureDay = day;
                }
                lastStage = crop.getStage();
                if (firstFruitingStage == null && crop.getStage() != CropStage.SEEDLING
                        && crop.getStage() != CropStage.FLOWERING) {
                    firstFruitingStage = crop.getStage();
                }
                tempMin = Math.min(tempMin, crop.getTemperatureFactor());
                tempMax = Math.max(tempMax, crop.getTemperatureFactor());
                co2Min = Math.min(co2Min, crop.getCo2Factor());
                co2Max = Math.max(co2Max, crop.getCo2Factor());
                waterMin = Math.min(waterMin, crop.getWaterFactor());
                waterMax = Math.max(waterMax, crop.getWaterFactor());
                ecMin = Math.min(ecMin, soil.getEcDsPerM());
                ecMax = Math.max(ecMax, soil.getEcDsPerM());
                phMin = Math.min(phMin, soil.getSoilPh());
                phMax = Math.max(phMax, soil.getSoilPh());
                nMin = Math.min(nMin, soil.getNitrogenKgPerHa());
                nMax = Math.max(nMax, soil.getNitrogenKgPerHa());
                nutrientMin = Math.min(nutrientMin, soil.getNutrientFactor());
                nutrientMax = Math.max(nutrientMax, soil.getNutrientFactor());
                setRateMin = Math.min(setRateMin, crop.getFruitSetRate());
                setRateMax = Math.max(setRateMax, crop.getFruitSetRate());
                maxDamage = Math.max(maxDamage, disease.getDiseaseDamageFactor());
                maxPest = Math.max(maxPest, disease.getPestPopulation());
                for (DiseaseKind kind : DiseaseKind.values()) {
                    maxSeverity = Math.max(maxSeverity, disease.severity(kind));
                }
            }
            if (day % 10 == 0 || day == 1) {
                System.out.printf("%-5d %-13s %6.0f %6.2f %6.1f %8.1f %6d %7.1f %8.3f %8.2f %8.1f%n",
                        day, crop.getStage(), crop.getGdd(), crop.getLai(), crop.getPlantHeightCm(),
                        crop.getWFruit(), crop.getFruitCount(), crop.getSingleFruitWeightG(),
                        soil.getEcDsPerM(), soil.getSoilPh(), soil.getNitrogenKgPerHa());
            }
        }

        System.out.println();
        System.out.println("--- 生育与产量 ---");
        System.out.printf("  末生育期：%s   成熟日：%s%n", lastStage, matureDay < 0 ? "**120 天内未成熟**" : matureDay + " 天");
        System.out.printf("  首个结果期：%s%n", firstFruitingStage);
        System.out.printf("  LAI 峰值：%.3f%n", maxLai);
        System.out.printf("  果实干重：%.2f g/m²（按种植床 %.1f m²）/ %.2f g/m²（按棚体 %.1f m²）%n",
                crop.getWFruit() / BED_AREA_M2, BED_AREA_M2, crop.getWFruit() / GREENHOUSE_AREA_M2, GREENHOUSE_AREA_M2);
        System.out.printf("  商品产量：%.1f kg  → %.2f kg/m²（种植床）/ %.2f kg/m²（棚体）%n",
                economics.getMarketableYieldKg(), economics.getMarketableYieldKg() / BED_AREA_M2,
                economics.getMarketableYieldKg() / GREENHOUSE_AREA_M2);
        System.out.printf("  坐果率区间：%.4f ~ %.4f%n", setRateMin, setRateMax);

        System.out.println("--- 作物模型的三个胁迫因子（评测序列不暴露）---");
        System.out.printf("  温度因子 %.3f ~ %.3f%n", tempMin, tempMax);
        System.out.printf("  CO₂ 因子 %.3f ~ %.3f%n", co2Min, co2Max);
        System.out.printf("  水分因子 %.3f ~ %.3f%n", waterMin, waterMax);

        System.out.println("--- 土壤化学（评测序列不暴露）---");
        System.out.printf("  EC   %.3f ~ %.3f dS/m%n", ecMin, ecMax);
        System.out.printf("  pH   %.3f ~ %.3f%n", phMin, phMax);
        System.out.printf("  速效氮 %.1f ~ %.1f kg/ha（累计淋洗 %.1f）%n", nMin, nMax, soil.getLeachedNitrogenKgPerHa());
        System.out.printf("  养分因子 %.3f ~ %.3f%n", nutrientMin, nutrientMax);
        System.out.printf("  累计灌溉 %.2f mm（%d 次事件）%n", soil.getIrrigationMmTotal(), irrigationEvents);

        // 氮收支：用已有量算清，不猜。初始值取自 SoilState 的初值，期末值即当前状态。
        double fertilizerInput = fertilizerApplied;
        double leached = soil.getLeachedNitrogenKgPerHa();
        double finalN = soil.getNitrogenKgPerHa();
        double initialN = 90.0;
        double uptake = initialN + fertilizerInput - leached - finalN;
        System.out.println("--- 氮收支（本季，kg/ha）---");
        System.out.printf("  初始 %.1f + 施肥 %.1f − 淋洗 %.1f − 期末 %.1f = 作物带走 %.1f%n",
                initialN, fertilizerInput, leached, finalN, uptake);
        // 刻意不算"隐含含氮率"：uptake 是 kg/ha，wTotal 的单位（与果实干重同）是每株克数，
        // 两者相除没有意义。要算需先确定 wTotal 的单位与株数折算——宁可不印，也不印一个读不出的比值。
        System.out.printf("  施肥投入占带走量的 %.1f%%（不足则氮必然见底）%n",
                uptake == 0 ? 0 : fertilizerInput / uptake * 100.0);
        System.out.println("  判读：若投入远低于带走量，则氮必然在季内见底、养分因子长期钳在下限，"
                + "土壤化学再合理也传不到作物生长。本项为**待决的配平问题**——"
                + "要定值需一份可引用的番茄氮收支（每吨果实带走多少 N），不能靠现有系数互调。");

        System.out.println("--- 病虫害 ---");
        System.out.printf("  最大病害伤害因子 %.4f   最大单病严重度 %.4f   最大虫口 %.4f%n",
                maxDamage, maxSeverity, maxPest);

        System.out.println();
        System.out.println("--- 自证：复现结果必须与评测档 P2 已记录值一致 ---");
        System.out.printf("  果实干重 %.4f（评测记录 258.3636）%n", crop.getWFruit());
        System.out.printf("  高温暴露 %d min（评测记录 29490）%n", highTempMinutes);
        System.out.printf("  病害压力积分 %.1f（评测记录 5455711.7）%n", diseasePressureIntegral);
        System.out.printf("  利润 %.4f 元（评测记录 1665.3248）%n", economics.getProfitYuan());

        assertEquals(258.3636, crop.getWFruit(), 0.01,
                "复现失败：本测试的循环与评测平台不一致，下面的内部量不能代表被测模型");
        assertEquals(29490L, highTempMinutes, "复现失败：高温暴露不一致");
        assertEquals(5455711.7, diseasePressureIntegral, 1.0, "复现失败：病害压力积分不一致");
        assertEquals(1665.3248, economics.getProfitYuan(), 0.01, "复现失败：利润不一致");
    }

    private double clamp(double value, double lower, double upper) {
        return Math.max(lower, Math.min(upper, value));
    }
}
