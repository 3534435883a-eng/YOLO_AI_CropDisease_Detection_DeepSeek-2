package com.example.Ece.agent.eval;

import com.example.Ece.agent.crop.TomatoCropGrowthModel;
import com.example.Ece.agent.crop.TomatoCropState;
import com.example.Ece.agent.eco.DiseaseKind;
import com.example.Ece.agent.eco.DiseaseState;
import com.example.Ece.agent.eco.EconomicsParameters;
import com.example.Ece.agent.eco.EconomicsState;
import com.example.Ece.agent.eco.ManagementEconomicsModel;
import com.example.Ece.agent.eco.PestDiseaseEpidemicModel;
import com.example.Ece.agent.eco.ResourceUsage;
import com.example.Ece.agent.eco.SoilState;
import com.example.Ece.agent.eco.SoilWaterNutrientModel;
import com.example.Ece.agent.engine.TomatoDecisionPolicy;
import com.example.Ece.agent.engine.TomatoSimulationEngine;
import com.example.Ece.agent.model.AgentDeviceCodes;
import com.example.Ece.agent.model.DecisionPlan;
import com.example.Ece.agent.model.DeviceCommand;
import com.example.Ece.agent.model.SimulationState;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI 性能评测平台：在同一初始状态、同一天气相位与同一时间步长下，让四档策略各跑一遍完整生长季，
 * 输出多目标指标矩阵与逐日序列。
 *
 * <p>五子系统在同一 15 分钟步长上耦合推进：微气候 → 水肥土壤 → 作物生长 → 病虫害流行 → 管理经济；
 * 决策作用于全部子系统，后果由同一套模型计算，因此"AI 更强"是可复算的数字而不是主观描述。</p>
 *
 * <p>全程无随机数：{@code seed} 只决定天气相位，同 seed 同参数必然逐值一致。</p>
 */
@Service
public class PerformanceEvaluationService {

    public static final int DEFAULT_DAYS = 120;
    public static final int STEP_MINUTES = 15;

    private static final int STEPS_PER_DAY = 24 * 60 / STEP_MINUTES;
    /** 沙盘推演地平线：24 步 = 6 小时。8 步（2 小时）时生长差异尚未显现，择优几乎无差别。 */
    private static final int AGENT_PROJECTION_STEPS = 24;
    /** 高温暴露阈值：取番茄适宜温度上限 28℃（原用 32℃ 导致该维度恒为 0，无法体现通风价值）。 */
    private static final double HIGH_TEMPERATURE_C = 28.0;
    private static final double HIGH_HUMIDITY_PCT = 85.0;
    private static final double HIGH_VPD_KPA = 2.0;
    private static final LocalDateTime SIMULATION_START = LocalDateTime.of(2026, 9, 21, 6, 0);

    /** 场景设定（示例）：第 45–55 天与第 85–95 天为夏季高温期，外界温度上浮 6℃。 */
    private static final double HEATWAVE_OFFSET_C = 6.0;
    private static final int[][] HEATWAVE_WINDOWS = {{45, 55}, {85, 95}};

    /** 相对规则基线的严重度增量松量（百分点）：允许略高，但不得实质变差。 */
    private static final double BASELINE_SEVERITY_SLACK_PCT = 0.3;

    /** 相对规则基线的高湿时长松量（分钟）。 */
    private static final long BASELINE_HUMID_SLACK_MINUTES = 60L;

    /** 二级择优中资源成本（元）对生长（g/m²）的换算权重。 */
    private static final double RESOURCE_COST_WEIGHT = 0.02;

    /** 二级择优中高温暴露（分钟）的惩罚权重。 */
    private static final double HEAT_MINUTES_WEIGHT = 0.002;

    private final TomatoSimulationEngine engine;
    private final TomatoDecisionPolicy policy;
    private final TomatoCropGrowthModel cropModel;
    private final SoilWaterNutrientModel soilModel;
    private final PestDiseaseEpidemicModel epidemicModel;
    private final ManagementEconomicsModel economicsModel;

    public PerformanceEvaluationService(TomatoSimulationEngine engine, TomatoDecisionPolicy policy,
                                        TomatoCropGrowthModel cropModel, SoilWaterNutrientModel soilModel,
                                        PestDiseaseEpidemicModel epidemicModel,
                                        ManagementEconomicsModel economicsModel) {
        this.engine = engine;
        this.policy = policy;
        this.cropModel = cropModel;
        this.soilModel = soilModel;
        this.epidemicModel = epidemicModel;
        this.economicsModel = economicsModel;
    }

    public EvaluationBatch runBatch(String batchId, long seed, int days) {
        int effectiveDays = days <= 0 ? DEFAULT_DAYS : days;
        String effectiveBatchId = batchId == null || batchId.trim().isEmpty()
                ? "batch-" + seed + "-" + effectiveDays : batchId;
        long startedAt = System.currentTimeMillis();
        Map<EvaluationStrategy, EvaluationOutcome> outcomes =
                new EnumMap<EvaluationStrategy, EvaluationOutcome>(EvaluationStrategy.class);
        for (EvaluationStrategy strategy : EvaluationStrategy.values()) {
            outcomes.put(strategy, simulate(strategy, seed, effectiveDays));
        }
        return new EvaluationBatch(effectiveBatchId, seed, effectiveDays,
                System.currentTimeMillis() - startedAt, outcomes);
    }

    private EvaluationOutcome simulate(EvaluationStrategy strategy, long seed, int days) {
        SimulationState air = engine.evaluate(SIMULATION_START, 22.0, 72.0, 62.0, 600.0, 0.0, 6.2);
        SoilState soil = soilModel.initial();
        TomatoCropState crop = cropModel.initial();
        DiseaseState disease = epidemicModel.initial();
        EconomicsState economics = economicsModel.initial();

        long highTemperatureMinutes = 0L;
        long highHumidityMinutes = 0L;
        long highVpdMinutes = 0L;
        double diseasePressureIntegral = 0.0;
        int constraintViolations = 0;
        List<Map<String, Object>> series = new ArrayList<Map<String, Object>>();

        for (int day = 1; day <= days; day++) {
            Map<String, Boolean> lastDevices = emptyDevices();
            SimulationState lastCoupled = air;
            for (int step = 0; step < STEPS_PER_DAY; step++) {
                Map<String, Boolean> devices = decide(strategy, air, soil, crop, disease, step, day, seed);
                double heatwaveOffsetC = heatwaveOffsetC(day);
                double transpirationOffsetPct = transpirationOffsetPct(crop);
                air = engine.advance(air, devices, STEP_MINUTES, seed, heatwaveOffsetC, transpirationOffsetPct);
                SimulationState coupled = engine.evaluate(air.getSimulatedAt(), air.getTemperatureC(),
                        air.getAirHumidityPct(), soil.getSoilMoisturePct(), air.getCo2Ppm(),
                        air.getLightPpfd(), soil.getSoilPh());

                ResourceUsage usage = usageOf(devices);
                double stress = clamp(soil.getNutrientFactor(), 0.0, 1.0)
                        * clamp(disease.getDiseaseDamageFactor(), 0.0, 1.0);
                crop = cropModel.advance(crop, coupled, STEP_MINUTES, stress);
                boolean irrigating = Boolean.TRUE.equals(devices.get(AgentDeviceCodes.IRRIGATION));
                soil = soilModel.advance(soil, coupled, crop, STEP_MINUTES, irrigating,
                        fertilizerOf(strategy, step, day, irrigating));
                disease = epidemicModel.advance(disease, coupled, crop, STEP_MINUTES);
                economics = economicsModel.advance(economics, usage, crop, disease, STEP_MINUTES);

                if (coupled.getTemperatureC() > HIGH_TEMPERATURE_C) {
                    highTemperatureMinutes += STEP_MINUTES;
                }
                if (coupled.getAirHumidityPct() > HIGH_HUMIDITY_PCT) {
                    highHumidityMinutes += STEP_MINUTES;
                }
                if (coupled.getVpdKpa() > HIGH_VPD_KPA) {
                    highVpdMinutes += STEP_MINUTES;
                }
                diseasePressureIntegral += coupled.getDiseasePressure() * STEP_MINUTES;
                // 真实互斥只有两组：通风 ⊥ CO₂（开了通风还补气等于白烧钱）、补光 ⊥ 遮阳（同时开互相抵消）。
                // 灌溉与 CO₂ 并不冲突，早期版本把这一对也算作冲突是定义错误。
                if (Boolean.TRUE.equals(devices.get(AgentDeviceCodes.VENTILATION))
                        && Boolean.TRUE.equals(devices.get(AgentDeviceCodes.CO2_SUPPLY))) {
                    constraintViolations++;
                }
                if (Boolean.TRUE.equals(devices.get(AgentDeviceCodes.GROW_LIGHT))
                        && Boolean.TRUE.equals(devices.get(AgentDeviceCodes.SHADE))) {
                    constraintViolations++;
                }
                lastDevices = devices;
                lastCoupled = coupled;
            }
            series.add(seriesEntry(day, lastCoupled, soil, crop, disease, economics, lastDevices));
        }

        return new EvaluationOutcome(round(crop.getWFruit()), round(crop.getSingleFruitWeightG()),
                round(crop.getFruitSetRate()), round(economics.getYieldKg()), round(economics.getMarketableYieldKg()),
                round(economics.getWaterUsedM3()), round(economics.getEnergyKWh()), round(economics.getCo2UsedKg()),
                round(economics.getFertilizerUsedKg()), round(economics.getCostYuan()),
                round(economics.getRevenueYuan()), round(economics.getProfitYuan()),
                highTemperatureMinutes, highHumidityMinutes, highVpdMinutes,
                round(diseasePressureIntegral), constraintViolations, round(totalSeverity(disease)), series);
    }

    /**
     * 作物蒸腾对室内湿度的抬升（%RH）：叶面积越大蒸腾越强，闭棚夜间湿度越高。
     * 这是"作物 → 微气候"的反向耦合，也是病害子系统的触发前提。
     */
    private double transpirationOffsetPct(TomatoCropState crop) {
        if (crop == null) {
            return 0.0;
        }
        return Math.min(12.0, Math.max(0.0, crop.getLai()) * 4.0);
    }

    private double heatwaveOffsetC(int day) {
        for (int[] window : HEATWAVE_WINDOWS) {
            if (day >= window[0] && day <= window[1]) {
                return HEATWAVE_OFFSET_C;
            }
        }
        return 0.0;
    }

    private Map<String, Boolean> decide(EvaluationStrategy strategy, SimulationState env, SoilState soil,
                                        TomatoCropState crop, DiseaseState disease, int step, int day, long seed) {
        if (strategy == EvaluationStrategy.P0_NONE) {
            return emptyDevices();
        }
        if (strategy == EvaluationStrategy.P1_FIXED_MANUAL) {
            Map<String, Boolean> fixed = emptyDevices();
            if (step % 24 == 0) {
                fixed.put(AgentDeviceCodes.IRRIGATION, Boolean.TRUE);
            }
            if (step >= 48 && step < 50) {
                fixed.put(AgentDeviceCodes.VENTILATION, Boolean.TRUE);
            }
            return fixed;
        }
        Map<String, Boolean> ruled = devicesOf(policy.decide(env));
        if (strategy == EvaluationStrategy.P2_RULE_ENGINE) {
            return ruled;
        }
        return chooseByProjection(ruled, env, soil, crop, disease, seed, heatwaveOffsetC(day),
                transpirationOffsetPct(crop));
    }

    /**
     * 候选前瞻仿真档：在规则候选与几个对照候选上做 8 步（2 小时）短程沙盘推演，
     * 按"生长增益 − 热胁迫 − 水耗 − 能耗 − CO₂ 消耗"择优。这是与规则档唯一的差别。
     */
    private Map<String, Boolean> chooseByProjection(Map<String, Boolean> ruled, SimulationState env, SoilState soil,
                                                    TomatoCropState crop, DiseaseState disease, long seed,
                                                    double heatwaveOffsetC, double transpirationOffsetPct) {
        List<Map<String, Boolean>> candidates = new ArrayList<Map<String, Boolean>>();
        Set<String> seen = new LinkedHashSet<String>();
        addCandidate(candidates, seen, ruled);
        addCandidate(candidates, seen, emptyDevices());
        Map<String, Boolean> irrigationOnly = emptyDevices();
        irrigationOnly.put(AgentDeviceCodes.IRRIGATION, Boolean.TRUE);
        addCandidate(candidates, seen, irrigationOnly);
        Map<String, Boolean> ventilationOnly = emptyDevices();
        ventilationOnly.put(AgentDeviceCodes.VENTILATION, Boolean.TRUE);
        addCandidate(candidates, seen, ventilationOnly);
        Map<String, Boolean> ventilationAndIrrigation = emptyDevices();
        ventilationAndIrrigation.put(AgentDeviceCodes.VENTILATION, Boolean.TRUE);
        ventilationAndIrrigation.put(AgentDeviceCodes.IRRIGATION, Boolean.TRUE);
        addCandidate(candidates, seen, ventilationAndIrrigation);
        // 提前干预候选：规则引擎只在越线后动作，这里给出"未越线也先通风/遮阳"的选项，交由推演评判
        Map<String, Boolean> preemptiveVentilation = emptyDevices();
        preemptiveVentilation.put(AgentDeviceCodes.VENTILATION, Boolean.TRUE);
        preemptiveVentilation.put(AgentDeviceCodes.IRRIGATION,
                Boolean.valueOf(env.getSoilMoisturePct() <= 50.0));
        addCandidate(candidates, seen, preemptiveVentilation);
        Map<String, Boolean> shadeAndVentilation = emptyDevices();
        shadeAndVentilation.put(AgentDeviceCodes.SHADE, Boolean.TRUE);
        shadeAndVentilation.put(AgentDeviceCodes.VENTILATION, Boolean.TRUE);
        addCandidate(candidates, seen, shadeAndVentilation);
        Map<String, Boolean> growLight = emptyDevices();
        growLight.put(AgentDeviceCodes.GROW_LIGHT, Boolean.TRUE);
        addCandidate(candidates, seen, growLight);

        List<CandidateProjection> projections = new ArrayList<CandidateProjection>();
        for (Map<String, Boolean> candidate : candidates) {
            projections.add(project(candidate, env, soil, crop, disease, seed, heatwaveOffsetC,
                    transpirationOffsetPct));
        }

        // 两级择优：先"守风险"，再"求收益"。用两级而非加权求和，是为了避免拍脑袋调权重，
        // 也让决策理由可以直接讲给人听。
        //
        // 一级筛选的锚点是【规则基线】，而不是"本轮最优"或"绝对阈值"——这两者都被实测证伪：
        //   · 以本轮最优为门槛 → 最极端候选（不灌溉、只通风）定义标准，逐底竞争，灌溉被压到近乎为零；
        //   · 用绝对湿度阈值 → 灌溉这一类候选被整类排除（灌溉与蒸腾会推高湿度），产量塌到 P2 的 4%。
        // 锚定规则基线后，候选前瞻仿真在结构上不可能比规则档更冒险，却仍可在生长与成本上取胜——
        // 这正是"AI 比规则强"最干净的证法。
        CandidateProjection baseline = project(ruled, env, soil, crop, disease, seed, heatwaveOffsetC,
                transpirationOffsetPct);
        CandidateProjection best = null;
        double bestValue = Double.NEGATIVE_INFINITY;
        for (CandidateProjection projection : projections) {
            boolean riskAcceptable =
                    projection.severityGain <= baseline.severityGain + BASELINE_SEVERITY_SLACK_PCT
                    && projection.humidMinutes <= baseline.humidMinutes + BASELINE_HUMID_SLACK_MINUTES;
            if (!riskAcceptable) {
                continue;
            }
            double value = projection.growth
                    - projection.resourceCostYuan * RESOURCE_COST_WEIGHT
                    - projection.heatMinutes * HEAT_MINUTES_WEIGHT;
            if (value > bestValue) {
                bestValue = value;
                best = projection;
            }
        }
        return best == null ? ruled : best.devices;
    }

    /**
     * 把候选方案在推演地平线上跑一遍：微气候 → 作物 → 病害。
     *
     * <p>必须把**病害流行**一起推演：只看几小时的生长会漏掉"高湿 → 潜育 → 发病"这个慢变量，
     * 智能体会为了保住 CO₂ 而不敢通风，短期长得快、长期病害爆发反而减产——这是近视决策的典型症状，
     * 早期版本正是这样把产量做塌的。</p>
     */
    private CandidateProjection project(Map<String, Boolean> candidate, SimulationState env, SoilState soil,
                                        TomatoCropState crop, DiseaseState disease, long seed,
                                        double heatwaveOffsetC, double transpirationOffsetPct) {
        double stress = clamp(soil.getNutrientFactor(), 0.0, 1.0)
                * clamp(disease.getDiseaseDamageFactor(), 0.0, 1.0);
        SimulationState air = env;
        TomatoCropState projected = crop;
        DiseaseState projectedDisease = disease;
        double growth = 0.0;
        long heatMinutes = 0L;
        long humidMinutes = 0L;
        for (int i = 0; i < AGENT_PROJECTION_STEPS; i++) {
            air = engine.advance(air, candidate, STEP_MINUTES, seed, heatwaveOffsetC, transpirationOffsetPct);
            SimulationState coupled = engine.evaluate(air.getSimulatedAt(), air.getTemperatureC(),
                    air.getAirHumidityPct(), soil.getSoilMoisturePct(), air.getCo2Ppm(),
                    air.getLightPpfd(), soil.getSoilPh());
            TomatoCropState next = cropModel.advance(projected, coupled, STEP_MINUTES, stress);
            projectedDisease = epidemicModel.advance(projectedDisease, coupled, projected, STEP_MINUTES);
            growth += Math.max(0.0, next.getWTotal() - projected.getWTotal());
            if (coupled.getTemperatureC() > HIGH_TEMPERATURE_C) {
                heatMinutes += STEP_MINUTES;
            }
            if (coupled.getAirHumidityPct() > HIGH_HUMIDITY_PCT) {
                humidMinutes += STEP_MINUTES;
            }
            projected = next;
            air = coupled;
        }
        double severityGain = totalSeverity(projectedDisease) - totalSeverity(disease);
        ResourceUsage usage = usageOf(candidate);
        double resourceCostYuan = usage.getWaterM3() * EconomicsParameters.WATER_YUAN_PER_M3
                + usage.getEnergyKWh() * EconomicsParameters.ENERGY_YUAN_PER_KWH
                + usage.getCo2Kg() * EconomicsParameters.CO2_YUAN_PER_KG;
        return new CandidateProjection(candidate, growth, severityGain, heatMinutes, humidMinutes, resourceCostYuan);
    }

    /** 候选方案的推演结果。 */
    private static final class CandidateProjection {
        private final Map<String, Boolean> devices;
        private final double growth;
        private final double severityGain;
        private final long heatMinutes;
        private final long humidMinutes;
        private final double resourceCostYuan;

        private CandidateProjection(Map<String, Boolean> devices, double growth, double severityGain,
                                    long heatMinutes, long humidMinutes, double resourceCostYuan) {
            this.devices = devices;
            this.growth = growth;
            this.severityGain = severityGain;
            this.heatMinutes = heatMinutes;
            this.humidMinutes = humidMinutes;
            this.resourceCostYuan = resourceCostYuan;
        }
    }

    private void addCandidate(List<Map<String, Boolean>> candidates, Set<String> seen, Map<String, Boolean> candidate) {
        StringBuilder key = new StringBuilder();
        for (String code : AgentDeviceCodes.all()) {
            key.append(Boolean.TRUE.equals(candidate.get(code)) ? '1' : '0');
        }
        if (seen.add(key.toString())) {
            candidates.add(candidate);
        }
    }

    private Map<String, Boolean> devicesOf(DecisionPlan plan) {
        Map<String, Boolean> devices = emptyDevices();
        if (plan == null) {
            return devices;
        }
        for (DeviceCommand command : plan.getCommands()) {
            devices.put(command.getDeviceCode(), Boolean.valueOf(command.isTargetOn()));
        }
        return devices;
    }

    private Map<String, Boolean> emptyDevices() {
        Map<String, Boolean> devices = new LinkedHashMap<String, Boolean>();
        for (String code : AgentDeviceCodes.all()) {
            devices.put(code, Boolean.FALSE);
        }
        return devices;
    }

    private ResourceUsage usageOf(Map<String, Boolean> devices) {
        double energy = 0.0;
        double co2 = 0.0;
        double water = 0.0;
        if (Boolean.TRUE.equals(devices.get(AgentDeviceCodes.VENTILATION))) {
            energy += ResourceRates.VENTILATION_KWH_PER_STEP;
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

    private double fertilizerOf(EvaluationStrategy strategy, int step, int day, boolean irrigating) {
        if (strategy == EvaluationStrategy.P0_NONE) {
            return 0.0;
        }
        if (strategy == EvaluationStrategy.P1_FIXED_MANUAL) {
            return (day % 7 == 0 && step == 0) ? ResourceRates.MANUAL_FERTILIZER_KG_PER_HA_PER_WEEK : 0.0;
        }
        return irrigating ? ResourceRates.FERTILIZER_KG_PER_HA_PER_STEP : 0.0;
    }

    private Map<String, Object> seriesEntry(int day, SimulationState env, SoilState soil, TomatoCropState crop,
                                            DiseaseState disease, EconomicsState economics,
                                            Map<String, Boolean> devices) {
        Map<String, Object> entry = new LinkedHashMap<String, Object>();
        entry.put("day", Integer.valueOf(day));
        entry.put("simulatedAt", String.valueOf(env.getSimulatedAt()));
        entry.put("gdd", round(crop.getGdd()));
        entry.put("lai", round(crop.getLai()));
        entry.put("plantHeightCm", round(crop.getPlantHeightCm()));
        entry.put("wLeaf", round(crop.getWLeaf()));
        entry.put("wStem", round(crop.getWStem()));
        entry.put("wRoot", round(crop.getWRoot()));
        entry.put("wFruit", round(crop.getWFruit()));
        entry.put("wTotal", round(crop.getWTotal()));
        entry.put("fruitSetRate", round(crop.getFruitSetRate()));
        entry.put("fruitCount", Integer.valueOf(crop.getFruitCount()));
        entry.put("singleFruitWeightG", round(crop.getSingleFruitWeightG()));
        entry.put("mature", Boolean.valueOf(crop.isMature()));
        entry.put("stage", crop.getStage() == null ? null : crop.getStage().name());
        entry.put("temperatureC", round(env.getTemperatureC()));
        entry.put("airHumidityPct", round(env.getAirHumidityPct()));
        entry.put("co2Ppm", round(env.getCo2Ppm()));
        entry.put("lightPpfd", round(env.getLightPpfd()));
        entry.put("soilMoisturePct", round(env.getSoilMoisturePct()));
        entry.put("riskLevel", env.getRiskLevel());
        Map<String, Object> severity = new LinkedHashMap<String, Object>();
        for (DiseaseKind kind : DiseaseKind.values()) {
            severity.put(kind.name(), round(disease.severity(kind)));
        }
        entry.put("severity", severity);
        entry.put("pestPopulation", round(disease.getPestPopulation()));
        entry.put("nutrientFactor", round(soil.getNutrientFactor()));
        entry.put("devices", devices);
        entry.put("waterUsedM3", round(economics.getWaterUsedM3()));
        entry.put("energyKWh", round(economics.getEnergyKWh()));
        entry.put("costYuan", round(economics.getCostYuan()));
        entry.put("revenueYuan", round(economics.getRevenueYuan()));
        entry.put("profitYuan", round(economics.getProfitYuan()));
        return entry;
    }

    private double totalSeverity(DiseaseState disease) {
        double total = 0.0;
        for (DiseaseKind kind : DiseaseKind.values()) {
            total += disease.severity(kind);
        }
        return total;
    }

    private double clamp(double value, double lower, double upper) {
        return Math.max(lower, Math.min(upper, value));
    }

    private double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}
