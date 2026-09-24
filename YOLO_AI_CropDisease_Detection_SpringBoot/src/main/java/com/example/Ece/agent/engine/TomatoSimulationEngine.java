package com.example.Ece.agent.engine;

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
    private static final double BED_ROOT_VOLUME_L = 4.0 * 21.0 * 1.7 * 0.25 * 1000.0;
    private static final double GREENHOUSE_AIR_VOLUME_M3 = 26.0 * 13.0 * (4.0 + 1.7 * 2.0 / Math.PI);
    private static final double IRRIGATION_L_PER_TICK = 60.0;

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
        double temperature = exchange(current.getTemperatureC(), outsideTemperature, 0.12, factor);
        vapor = exchange(vapor, outsideVapor + saturationVaporPressure(outsideTemperature)
                * interiorHumidityOffsetPct / 100.0, 0.09, factor);
        double soil = current.getSoilMoisturePct() - (0.016 + Math.max(0.0, temperature - 20.0) * 0.001) * factor;
        double co2 = exchange(current.getCo2Ppm(), 420.0, 0.08, factor);
        double light = exchange(current.getLightPpfd(), outsideLight, 0.45, factor);
        double soilPh = current.getSoilPh();

        if (isOn(states, AgentDeviceCodes.IRRIGATION)) {
            soil += 100.0 * IRRIGATION_L_PER_TICK / BED_ROOT_VOLUME_L * factor;
            vapor += saturationVaporPressure(temperature) * 0.011 * factor;
        }
        if (isOn(states, AgentDeviceCodes.VENTILATION)) {
            temperature = exchange(temperature, outsideTemperature, 0.35, factor);
            vapor = exchange(vapor, outsideVapor, 0.35, factor);
            co2 = exchange(co2, 420.0, 0.35, factor);
        }
        if (isOn(states, AgentDeviceCodes.ROOF_VENT)) {
            temperature = exchange(temperature, outsideTemperature, 0.15, factor);
            vapor = exchange(vapor, outsideVapor, 0.15, factor);
            co2 = exchange(co2, 420.0, 0.15, factor);
        }
        if (isOn(states, AgentDeviceCodes.EXHAUST_FAN)) {
            double inletTemperature = outsideTemperature;
            double inletVapor = outsideVapor;
            if (isOn(states, AgentDeviceCodes.COOLING_PAD)) {
                inletTemperature = padInletTemperature(outsideTemperature, outsideVapor);
                inletVapor = padInletVapor(outsideTemperature, outsideVapor, inletTemperature);
            }
            temperature = exchange(temperature, inletTemperature, 0.35, factor);
            vapor = exchange(vapor, inletVapor, 0.35, factor);
            co2 = exchange(co2, 420.0, 0.35, factor);
        }
        if (isOn(states, AgentDeviceCodes.GROW_LIGHT)) {
            light += 190.0 * factor;
            temperature += 0.18 * factor;
        }
        if (isOn(states, AgentDeviceCodes.SHADE)) {
            temperature -= 0.9 * factor;
            light -= 125.0 * factor;
        }
        if (isOn(states, AgentDeviceCodes.CO2_SUPPLY) && !isOn(states, AgentDeviceCodes.VENTILATION)
                && !isOn(states, AgentDeviceCodes.ROOF_VENT)
                && !isOn(states, AgentDeviceCodes.EXHAUST_FAN)) {
            co2 += 0.250 / 0.04401 * 8.314 * (current.getTemperatureC() + 273.15)
                    / (101325.0 * GREENHOUSE_AIR_VOLUME_M3) * 1000000.0 * factor;
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
            if (saturationVaporPressure(midpoint) - 0.066 * (dryBulb - midpoint) > vaporPressure) {
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
        return outsideTemperature - 0.8 * (outsideTemperature - wetBulbTemperature(outsideTemperature, outsideVapor));
    }

    private double padInletVapor(double outsideTemperature, double outsideVapor, double inletTemperature) {
        return Math.min(saturationVaporPressure(inletTemperature),
                outsideVapor + 0.066 * (outsideTemperature - inletTemperature));
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
