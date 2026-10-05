package com.example.Ece.agent.engine;

import com.example.Ece.agent.profile.HortiM3Profile;

import com.example.Ece.agent.model.AgentDeviceCodes;
import com.example.Ece.agent.model.SimulationState;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Deterministic, documented simulation for a tomato greenhouse. It is deliberately
 * a scenario model: no output from this class represents an observed measurement.
 */
@Component
public class TomatoSimulationEngine {
    private static final double BED_ROOT_VOLUME_L = HortiM3Profile.TOTAL_EXPERIMENT_AREA_M2 * HortiM3Profile.ASSUMED_EFFECTIVE_ROOT_DEPTH_M * 1000.0;
    /**
     * 棚体占地面积（m²）：M3 公开尺寸 40 m × 40 m。空气体积采用拱形剖面近似，非实测。
     *
     * <p>**公开**的理由与 {@link #IRRIGATION_L_PER_TICK} 相同：评测侧的耗能定额是**按棚体面积**估的，
     * 而棚体尺寸只能有一个定义。2026-09-26 发现 {@code ResourceRates} 的耗能定额按"500 m² 温室"估算，
     * 而本模型的棚体是 338 m²——**凭空多算 1.48 倍**。同一个规模基准散落在注释里靠人记，
     * 就会这样各写各的。</p>
     */
    public static final double GREENHOUSE_FOOTPRINT_M2 = HortiM3Profile.GREENHOUSE_FOOTPRINT_M2;

    private static final double GREENHOUSE_AIR_VOLUME_M3 = GREENHOUSE_FOOTPRINT_M2 * (HortiM3Profile.EAVE_HEIGHT_M
            + (HortiM3Profile.RIDGE_HEIGHT_M - HortiM3Profile.EAVE_HEIGHT_M) * 2.0 / Math.PI);
    /**
     * 单步滴灌水量（L）。**公开**是为了让评测侧的成本记账能与物理侧对齐：
     * {@code ResourceRates.IRRIGATION_M3_PER_STEP} 必须等于本值 / 1000，
     * 否则会出现"浇 60 L、记 600 L"这类不一致（2026-09-26 实际发生过，见 ResourceRates 注释）。
     * {@code ResourceAccountingTest} 锁住这个等式。
     */
    public static final double IRRIGATION_L_PER_TICK = 60.0;

    /** 每 15 分钟参考出力的加热温升；未校准的响应代理量。 */
    public static final double ASSUMED_HEATING_C_PER_REFERENCE_TICK = 1.2;

    // ---- 湿空气交换与湿帘参数 ----------------------------------------------------
    // 这几个是 docs/tomato-greenhouse-agent.md「仿真依据与参数边界」里**明确点名**的未校准假设，
    // 原先内联在方法体里（魔法数字），无法被参数出处登记表枚举。此处提升为具名常量：
    // 纯改名、不改数值，因此不改变任何推演结果（`tools/eval-audit` 的可复算性核对可证）。
    // 数值仍为未核实假设，不得当作工程整定值。

    /** 基础换热系数（每 15 分钟步长）。未校准假设。 */
    public static final double BASE_HEAT_EXCHANGE_PER_TICK = 0.12;

    /** 基础空气水汽交换系数（每步）。未校准假设。 */
    public static final double BASE_VAPOR_EXCHANGE_PER_TICK = 0.09;

    /** 侧窗/通风的附加交换系数。未校准假设。 */
    public static final double VENTILATION_EXCHANGE_PER_TICK = 0.35;

    /** 屋窗的附加交换系数。未校准假设。 */
    public static final double ROOF_VENT_EXCHANGE_PER_TICK = 0.15;

    /** 强制排风的附加交换系数。未校准假设。 */
    public static final double EXHAUST_FAN_EXCHANGE_PER_TICK = 0.35;

    /** 湿帘效率：进风温度向湿球靠近的比例，取 80%。未校准假设，非厂商实测效率。 */
    public static final double COOLING_PAD_EFFICIENCY = 0.8;

    /** 干湿球关系的湿度计常数 γ（kPa/℃），近海平面近似值，随海拔变化。 */
    public static final double PSYCHROMETRIC_CONSTANT_KPA_PER_C = 0.066;

    public SimulationState evaluate(LocalDateTime simulatedAt, double temperatureC, double airHumidityPct,
                                    double soilMoisturePct, double co2Ppm, double lightPpfd, double soilPh) {
        double vpd = calculateVpd(temperatureC, airHumidityPct);
        double temperatureRisk = rangeRisk(temperatureC, 18.0, 28.0, 14.0, 34.0);
        double humidityRisk = rangeRisk(airHumidityPct, 60.0, 80.0, 40.0, 95.0);
        double soilRisk = rangeRisk(soilMoisturePct, 48.0, 75.0, 28.0, 90.0);
        double co2Risk = rangeRisk(co2Ppm, 650.0, 1050.0, 350.0, 1400.0);
        double lightRisk = rangeRisk(lightPpfd, 280.0, 850.0, 40.0, 1400.0);
        double vpdRisk = rangeRisk(vpd, 0.6, 1.5, 0.15, 2.5);
        double environmentRisk = clamp(0.25 * temperatureRisk + 0.20 * humidityRisk + 0.20 * soilRisk
                + 0.12 * co2Risk + 0.10 * lightRisk + 0.13 * vpdRisk, 0.0, 100.0);

        double temperatureSuitability = temperatureC >= 20.0 && temperatureC <= 26.0 ? 28.0 : 8.0;
        double humidityPressure = clamp((airHumidityPct - 70.0) * 1.9, 0.0, 48.0);
        double lowLightPressure = lightPpfd < 250.0 ? 14.0 : 0.0;
        double wetSoilPressure = soilMoisturePct > 78.0 ? 12.0 : 0.0;
        double diseasePressure = clamp(temperatureSuitability + humidityPressure + lowLightPressure + wetSoilPressure,
                0.0, 100.0);
        double totalRisk = Math.max(environmentRisk, diseasePressure);
        String riskLevel = totalRisk >= 70.0 ? "HIGH" : totalRisk >= 40.0 ? "MEDIUM" : "LOW";

        return new SimulationState(simulatedAt, round(temperatureC, 2), round(airHumidityPct, 2),
                round(soilMoisturePct, 2), round(co2Ppm, 2), round(lightPpfd, 2), round(soilPh, 2),
                round(vpd, 3), round(environmentRisk, 2), round(diseasePressure, 2), riskLevel);
    }

    public SimulationState advance(SimulationState current, Map<String, Boolean> deviceStates,
                                   int tickMinutes, long seed) {
        return advance(current, deviceStates, tickMinutes, seed, 0.0);
    }

    /**
     * 带外界温度偏移的推进，用于模拟夏季高温期等天气情景（评测平台需要"热到必须干预"的场景，
     * 否则通风设备永远不会被触发，风险维度也就没有区分度）。
     *
     * @param outsideTemperatureOffsetC 外界温度上浮量（℃），0 表示常年基准情景
     */
    public SimulationState advance(SimulationState current, Map<String, Boolean> deviceStates,
                                   int tickMinutes, long seed, double outsideTemperatureOffsetC) {
        return advance(current, deviceStates, tickMinutes, seed, outsideTemperatureOffsetC, 0.0);
    }

    /**
     * 完整推进：同时接受外界温度偏移与**室内湿度源**（作物蒸腾）。
     *
     * <p>真实温室夜间湿度由作物蒸腾主导——闭棚一晚常达 90% 以上，这也是灰霉、晚疫等
     * 高湿型病害的侵染前提。缺少这一项时室内湿度只能趋近外界湿度（约 84%），
     * 病害子系统将永远不触发。</p>
     *
     * @param interiorHumidityOffsetPct 作物蒸腾带来的湿度抬升（%RH），叠加到湿度平衡目标上
     */
    public SimulationState advance(SimulationState current, Map<String, Boolean> deviceStates,
                                   int tickMinutes, long seed, double outsideTemperatureOffsetC,
                                   double interiorHumidityOffsetPct) {
        return advance(current, deviceStates, tickMinutes, seed, outsideTemperatureOffsetC,
                interiorHumidityOffsetPct, null);
    }

    /**
     * 带**连续控制量**的推进：每个设备除开关外还可给出 duty ∈ [0,1] 的出力比例，
     * 用来表达可调风机档位、可调遮阳开度与可调 CO₂ 流量。
     *
     * <p>{@code dutyCycles} 为 null 或未包含某设备时，该设备出力按 1.0 计算，
     * 因此所有既有调用（规则基线、人工档、候选推演）的数值**逐位不变**——
     * 这不是"近似相等"，而是同一条表达式的同一个浮点结果（乘 1.0）。</p>
     *
     * <p>灌溉不在此列：它同时耦合土壤水肥、施肥与成本台账，本版仍由规则侧二值控制，
     * 以免把连续调节的效果与灌溉制度变化混在一起。</p>
     */
    public SimulationState advance(SimulationState current, Map<String, Boolean> deviceStates,
                                   int tickMinutes, long seed, double outsideTemperatureOffsetC,
                                   double interiorHumidityOffsetPct, Map<String, Double> dutyCycles) {
        Map<String, Boolean> states = deviceStates == null ? Collections.<String, Boolean>emptyMap() : deviceStates;
        double factor = Math.max(1, tickMinutes) / 15.0;
        LocalDateTime nextAt = current.getSimulatedAt().plusMinutes(tickMinutes);
        double clockHour = nextAt.getHour() + nextAt.getMinute() / 60.0;
        double daylight = Math.max(0.0, Math.sin((clockHour - 6.0) * Math.PI / 12.0));
        double outsideTemperature = outsideTemperature(nextAt, seed) + outsideTemperatureOffsetC;
        double outsideHumidity = outsideHumidity(nextAt, seed);
        double outsideLight = 820.0 * daylight;

        double outsideVapor = saturationVaporPressure(outsideTemperature) * outsideHumidity / 100.0;
        double vapor = saturationVaporPressure(current.getTemperatureC()) * current.getAirHumidityPct() / 100.0;
        double temperature = exchange(current.getTemperatureC(), outsideTemperature, BASE_HEAT_EXCHANGE_PER_TICK, factor);
        vapor = exchange(vapor, outsideVapor + saturationVaporPressure(outsideTemperature)
                * interiorHumidityOffsetPct / 100.0, BASE_VAPOR_EXCHANGE_PER_TICK, factor);
        double soil = current.getSoilMoisturePct() - (0.016 + Math.max(0.0, temperature - 20.0) * 0.001) * factor;
        double co2 = exchange(current.getCo2Ppm(), 420.0, 0.08, factor);
        double light = exchange(current.getLightPpfd(), outsideLight, 0.45, factor);
        double soilPh = current.getSoilPh();

        if (isOn(states, AgentDeviceCodes.IRRIGATION)) {
            soil += 100.0 * IRRIGATION_L_PER_TICK / BED_ROOT_VOLUME_L * factor;
            vapor += saturationVaporPressure(temperature) * 0.011 * factor;
        }
        if (isOn(states, AgentDeviceCodes.VENTILATION)) {
            double duty = duty(dutyCycles, AgentDeviceCodes.VENTILATION);
            temperature = exchange(temperature, outsideTemperature, VENTILATION_EXCHANGE_PER_TICK * duty, factor);
            vapor = exchange(vapor, outsideVapor, VENTILATION_EXCHANGE_PER_TICK * duty, factor);
            co2 = exchange(co2, 420.0, VENTILATION_EXCHANGE_PER_TICK * duty, factor);
        }
        if (isOn(states, AgentDeviceCodes.ROOF_VENT)) {
            double duty = duty(dutyCycles, AgentDeviceCodes.ROOF_VENT);
            temperature = exchange(temperature, outsideTemperature, ROOF_VENT_EXCHANGE_PER_TICK * duty, factor);
            vapor = exchange(vapor, outsideVapor, ROOF_VENT_EXCHANGE_PER_TICK * duty, factor);
            co2 = exchange(co2, 420.0, ROOF_VENT_EXCHANGE_PER_TICK * duty, factor);
        }
        if (isOn(states, AgentDeviceCodes.EXHAUST_FAN)) {
            double duty = duty(dutyCycles, AgentDeviceCodes.EXHAUST_FAN);
            double inletTemperature = outsideTemperature;
            double inletVapor = outsideVapor;
            if (isOn(states, AgentDeviceCodes.COOLING_PAD)) {
                double padDuty = duty(dutyCycles, AgentDeviceCodes.COOLING_PAD);
                // 按 padDuty 在"室外空气"与"湿帘出风"之间线性插值；padDuty=1 时与旧行为逐位一致。
                inletTemperature = padInletTemperature(outsideTemperature, outsideVapor) * padDuty
                        + outsideTemperature * (1.0 - padDuty);
                inletVapor = padInletVapor(outsideTemperature, outsideVapor,
                        padInletTemperature(outsideTemperature, outsideVapor)) * padDuty
                        + outsideVapor * (1.0 - padDuty);
            }
            temperature = exchange(temperature, inletTemperature, EXHAUST_FAN_EXCHANGE_PER_TICK * duty, factor);
            vapor = exchange(vapor, inletVapor, EXHAUST_FAN_EXCHANGE_PER_TICK * duty, factor);
            co2 = exchange(co2, 420.0, EXHAUST_FAN_EXCHANGE_PER_TICK * duty, factor);
        }
        if (isOn(states, AgentDeviceCodes.GROW_LIGHT)) {
            double duty = duty(dutyCycles, AgentDeviceCodes.GROW_LIGHT);
            light += 190.0 * duty * factor;
            temperature += 0.18 * duty * factor;
        }
        if (isOn(states, AgentDeviceCodes.HEATING)) {
            temperature += ASSUMED_HEATING_C_PER_REFERENCE_TICK * duty(dutyCycles, AgentDeviceCodes.HEATING) * factor;
        }
        if (isOn(states, AgentDeviceCodes.SHADE)) {
            double duty = duty(dutyCycles, AgentDeviceCodes.SHADE);
            temperature -= 0.9 * duty * factor;
            light -= 125.0 * duty * factor;
        }
        if (isOn(states, AgentDeviceCodes.CO2_SUPPLY) && !isOn(states, AgentDeviceCodes.VENTILATION)
                && !isOn(states, AgentDeviceCodes.ROOF_VENT)
                && !isOn(states, AgentDeviceCodes.EXHAUST_FAN)) {
            double duty = duty(dutyCycles, AgentDeviceCodes.CO2_SUPPLY);
            co2 += 0.250 / 0.04401 * 8.314 * (current.getTemperatureC() + 273.15)
                    / (101325.0 * GREENHOUSE_AIR_VOLUME_M3) * 1000000.0 * duty * factor;
        }

        double humidity = 100.0 * vapor / saturationVaporPressure(temperature);
        return evaluate(nextAt, clamp(temperature, 8.0, 45.0), clamp(humidity, 25.0, 99.0),
                clamp(soil, 5.0, 100.0), clamp(co2, 250.0, 1800.0), clamp(light, 0.0, 1800.0),
                clamp(soilPh, 4.0, 8.5));
    }

    public double calculateVpd(double temperatureC, double airHumidityPct) {
        return saturationVaporPressure(temperatureC) * (1.0 - clamp(airHumidityPct, 0.0, 100.0) / 100.0);
    }

    private double saturationVaporPressure(double temperatureC) {
        return 0.6108 * Math.exp((17.27 * temperatureC) / (temperatureC + 237.3));
    }

    private double exchange(double current, double target, double fractionPerTick, double tickFactor) {
        return current + (target - current) * (1.0 - Math.pow(1.0 - fractionPerTick, tickFactor));
    }

    private double wetBulbTemperature(double dryBulb, double vaporPressure) {
        double low = -20.0;
        double high = dryBulb;
        for (int iteration = 0; iteration < 30; iteration++) {
            double midpoint = (low + high) / 2.0;
            if (saturationVaporPressure(midpoint) - PSYCHROMETRIC_CONSTANT_KPA_PER_C * (dryBulb - midpoint) > vaporPressure) {
                high = midpoint;
            } else {
                low = midpoint;
            }
        }
        return (low + high) / 2.0;
    }

    public double coolingPadEvaporationLiters(SimulationState current, int tickMinutes, long seed) {
        LocalDateTime nextAt = current.getSimulatedAt().plusMinutes(tickMinutes);
        double outsideTemperature = outsideTemperature(nextAt, seed);
        double outsideVapor = saturationVaporPressure(outsideTemperature) * outsideHumidity(nextAt, seed) / 100.0;
        double inletTemperature = padInletTemperature(outsideTemperature, outsideVapor);
        double inletVapor = padInletVapor(outsideTemperature, outsideVapor, inletTemperature);
        double incomingDensity = 2167.0 * inletVapor / (inletTemperature + 273.15);
        double outsideDensity = 2167.0 * outsideVapor / (outsideTemperature + 273.15);
        double exchangedFraction = 1.0 - Math.pow(0.65, Math.max(1, tickMinutes) / 15.0);
        return Math.max(0.0, incomingDensity - outsideDensity)
                * GREENHOUSE_AIR_VOLUME_M3 * exchangedFraction / 1000.0;
    }

    private double padInletTemperature(double outsideTemperature, double outsideVapor) {
        return outsideTemperature - COOLING_PAD_EFFICIENCY * (outsideTemperature - wetBulbTemperature(outsideTemperature, outsideVapor));
    }

    private double padInletVapor(double outsideTemperature, double outsideVapor, double inletTemperature) {
        return Math.min(saturationVaporPressure(inletTemperature),
                outsideVapor + PSYCHROMETRIC_CONSTANT_KPA_PER_C * (outsideTemperature - inletTemperature));
    }

    private double outsideHumidity(LocalDateTime at, long seed) {
        double clockHour = at.getHour() + at.getMinute() / 60.0;
        double daylight = Math.max(0.0, Math.sin((clockHour - 6.0) * Math.PI / 12.0));
        double phase = Math.floorMod(seed, 360L) * Math.PI / 180.0;
        return clamp(84.0 - 32.0 * daylight + Math.cos(phase) * 3.0, 25.0, 98.0);
    }

    public double outsideTemperature(LocalDateTime at, long seed) {
        double clockHour = at.getHour() + at.getMinute() / 60.0;
        double daylight = Math.max(0.0, Math.sin((clockHour - 6.0) * Math.PI / 12.0));
        double phase = Math.floorMod(seed, 360L) * Math.PI / 180.0;
        return round(17.0 + 12.0 * daylight + Math.sin(phase) * 1.2, 2);
    }

    private boolean isOn(Map<String, Boolean> deviceStates, String code) {
        return Boolean.TRUE.equals(deviceStates.get(code));
    }

    /** 设备出力比例：缺省 1.0（等价于"全开"），并夹到 [0,1] 以免连续控制器写出越界值。 */
    private double duty(Map<String, Double> dutyCycles, String code) {
        if (dutyCycles == null) {
            return 1.0;
        }
        Double value = dutyCycles.get(code);
        if (value == null || value.isNaN()) {
            return 1.0;
        }
        return clamp(value.doubleValue(), 0.0, 1.0);
    }

    private double rangeRisk(double value, double preferredLow, double preferredHigh, double hardLow, double hardHigh) {
        if (value >= preferredLow && value <= preferredHigh) {
            return 0.0;
        }
        if (value < preferredLow) {
            return clamp((preferredLow - value) / (preferredLow - hardLow) * 100.0, 0.0, 100.0);
        }
        return clamp((value - preferredHigh) / (hardHigh - preferredHigh) * 100.0, 0.0, 100.0);
    }

    private double clamp(double value, double lower, double upper) {
        return Math.max(lower, Math.min(upper, value));
    }

    private double round(double value, int scale) {
        double multiplier = Math.pow(10.0, scale);
        return Math.round(value * multiplier) / multiplier;
    }
}
