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
        double phase = Math.floorMod(seed, 360L) * Math.PI / 180.0;
        double daylight = Math.max(0.0, Math.sin((clockHour - 6.0) * Math.PI / 12.0));
        double weatherBias = Math.sin(phase) * 1.2;
        double outsideTemperature = 17.0 + 12.0 * daylight + weatherBias + outsideTemperatureOffsetC;
        double outsideHumidity = 84.0 - 32.0 * daylight + Math.cos(phase) * 3.0 + interiorHumidityOffsetPct;
        double outsideLight = 820.0 * daylight;

        double temperature = current.getTemperatureC() + (outsideTemperature - current.getTemperatureC()) * 0.12 * factor;
        double humidity = current.getAirHumidityPct() + (outsideHumidity - current.getAirHumidityPct()) * 0.09 * factor;
        double soil = current.getSoilMoisturePct() - (0.42 + Math.max(0.0, temperature - 20.0) * 0.025) * factor;
        double co2 = current.getCo2Ppm() + (420.0 - current.getCo2Ppm()) * 0.08 * factor;
        double light = current.getLightPpfd() + (outsideLight - current.getLightPpfd()) * 0.45 * factor;
        double soilPh = current.getSoilPh();

        if (isOn(states, AgentDeviceCodes.IRRIGATION)) {
            soil += 5.6 * factor;
            humidity += 1.1 * factor;
        }
        if (isOn(states, AgentDeviceCodes.VENTILATION)) {
            temperature -= 1.6 * factor;
            humidity -= 5.2 * factor;
            co2 -= 48.0 * factor;
        }
        if (isOn(states, AgentDeviceCodes.GROW_LIGHT)) {
            light += 190.0 * factor;
            temperature += 0.18 * factor;
        }
        if (isOn(states, AgentDeviceCodes.SHADE)) {
            temperature -= 0.9 * factor;
            light -= 125.0 * factor;
        }
        if (isOn(states, AgentDeviceCodes.CO2_SUPPLY) && !isOn(states, AgentDeviceCodes.VENTILATION)) {
            co2 += 115.0 * factor;
        }

        return evaluate(nextAt, clamp(temperature, 8.0, 45.0), clamp(humidity, 25.0, 99.0),
                clamp(soil, 5.0, 100.0), clamp(co2, 250.0, 1800.0), clamp(light, 0.0, 1800.0),
                clamp(soilPh, 4.0, 8.5));
    }

    public double calculateVpd(double temperatureC, double airHumidityPct) {
        double saturationVaporPressure = 0.6108 * Math.exp((17.27 * temperatureC) / (temperatureC + 237.3));
        return saturationVaporPressure * (1.0 - clamp(airHumidityPct, 0.0, 100.0) / 100.0);
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
