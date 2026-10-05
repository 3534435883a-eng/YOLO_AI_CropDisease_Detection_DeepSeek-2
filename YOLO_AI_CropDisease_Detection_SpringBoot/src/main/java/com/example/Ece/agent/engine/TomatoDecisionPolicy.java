package com.example.Ece.agent.engine;

import com.example.Ece.agent.eco.SoilParameters;
import com.example.Ece.agent.eval.ResourceRates;
import com.example.Ece.agent.profile.HortiM3Profile;
import com.example.Ece.agent.model.AgentDeviceCodes;
import com.example.Ece.agent.model.DecisionPlan;
import com.example.Ece.agent.model.DeviceCommand;
import com.example.Ece.agent.model.SimulationState;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Rule layer. It proposes actions; it never writes the database or controls a device itself. */
@Component
public class TomatoDecisionPolicy {

    public DecisionPlan decide(SimulationState state) {
        return decide(state, HortiM3Profile.AGENT_TICK_MINUTES);
    }

    public DecisionPlan decide(SimulationState state, int tickMinutes) {
        Map<String, DeviceCommand> commands = new LinkedHashMap<>();
        for (String code : AgentDeviceCodes.all()) {
            commands.put(code, idleCommand(code));
        }

        List<String> reasons = new ArrayList<>();
        boolean urgent = "HIGH".equals(state.getRiskLevel());
        if (state.getTemperatureC() >= 29.0 || state.getAirHumidityPct() >= 82.0) {
            String summary = state.getTemperatureC() >= 29.0 ? "温度偏高，优先通风降温" : "空气湿度偏高，优先通风排湿";
            commands.put(AgentDeviceCodes.VENTILATION, command(AgentDeviceCodes.VENTILATION, true,
                    "VENTILATE", 1, summary, "ENERGY", urgent));
            reasons.add(summary);
        }
        if (state.getTemperatureC() >= 27.0 || state.getAirHumidityPct() >= 80.0) {
            commands.put(AgentDeviceCodes.ROOF_VENT, command(AgentDeviceCodes.ROOF_VENT, true,
                    "ROOF_AIR_EXCHANGE", 2, "屋窗辅助自然换气", "ENERGY", urgent));
        }
        if (state.getTemperatureC() >= 32.0 || state.getAirHumidityPct() >= 88.0) {
            commands.put(AgentDeviceCodes.EXHAUST_FAN, command(AgentDeviceCodes.EXHAUST_FAN, true,
                    "EXHAUST_HEAT_HUMIDITY", 1, "强制排风形成穿堂气流", "ENERGY", urgent));
            reasons.add("强制排风");
        }
        if (state.getTemperatureC() >= 33.0 && state.getAirHumidityPct() < 80.0) {
            commands.put(AgentDeviceCodes.COOLING_PAD, command(AgentDeviceCodes.COOLING_PAD, true,
                    "EVAPORATIVE_COOLING", 2, "高温且湿度允许，排风配合湿帘降温", "WATER", urgent));
            reasons.add("湿帘蒸发降温");
        }
        if (state.getAirHumidityPct() >= 75.0 || state.getTemperatureC() >= 27.0) {
            commands.put(AgentDeviceCodes.CIRCULATION_FAN, command(AgentDeviceCodes.CIRCULATION_FAN, true,
                    "MIX_CANOPY_AIR", 3, "环流混合冠层空气", "ENERGY", false));
        }
        if (state.getTemperatureC() >= 30.0 || state.getLightPpfd() >= 900.0) {
            commands.put(AgentDeviceCodes.SHADE, command(AgentDeviceCodes.SHADE, true,
                    "SHADE_HEAT", 2, "高温或强光，启用遮阳降低热负荷", "ENERGY", urgent));
            reasons.add("遮阳降低热负荷");
        }
        // M3 文献有加热设备，阈值、出力和能耗为本模型假设。
        if (state.getTemperatureC() < 18.0) {
            commands.put(AgentDeviceCodes.HEATING, command(AgentDeviceCodes.HEATING, true,
                    "HEAT_COLD", 2, "温度偏低，启动模拟加热", "ENERGY", urgent));
            reasons.add("模拟加热");
        }
        // 灌溉触发阈值取「田间持水量 × 85%」，出处：马志军等《水氮互作对设施番茄土壤氮平衡及
        // 氮素利用效率的影响研究》（北京水务 2024(5)，DOI 10.19671/j.1673-4637.2024.05.002）
        // ——该文结论为设施番茄适宜灌水下限为田间持水量的 85%。
        // 此前固定用 45 %vol，只相当于田间持水量（75 %vol）的 60%：灌溉明显偏晚，
        // 作物在灌前要经历一段水分胁迫。该胁迫此前不可见（因土壤水分口径不一致），修好后才发现。
        if (state.getSoilMoisturePct() <= SoilParameters.FIELD_CAPACITY_PCT
                * SoilParameters.IRRIGATION_TRIGGER_FRACTION_OF_FC) {
            commands.put(AgentDeviceCodes.IRRIGATION, command(AgentDeviceCodes.IRRIGATION, true,
                    "IRRIGATE_DRY", 1, "基质水分模型量偏低，执行小水滴灌", "WATER", urgent));
            reasons.add("补充根区水分");
        }
        if (state.getLightPpfd() <= 280.0 && !commands.get(AgentDeviceCodes.SHADE).isTargetOn()) {
            commands.put(AgentDeviceCodes.GROW_LIGHT, command(AgentDeviceCodes.GROW_LIGHT, true,
                    "SUPPLEMENT_LIGHT", 3, "有效光照不足，开启补光", "ENERGY", false));
            reasons.add("补充有效光照");
        }
        if (state.getCo2Ppm() <= 650.0 && !commands.get(AgentDeviceCodes.VENTILATION).isTargetOn()
                && !commands.get(AgentDeviceCodes.ROOF_VENT).isTargetOn()
                && !commands.get(AgentDeviceCodes.EXHAUST_FAN).isTargetOn()) {
            commands.put(AgentDeviceCodes.CO2_SUPPLY, command(AgentDeviceCodes.CO2_SUPPLY, true,
                    "SUPPLY_CO2", 4, "CO2 浓度偏低，补充 CO2", "CO2", false));
            reasons.add("补充 CO2");
        }

        // Ventilation always wins over CO2 supply in automatic mode.
        if (commands.get(AgentDeviceCodes.VENTILATION).isTargetOn()
                || commands.get(AgentDeviceCodes.ROOF_VENT).isTargetOn()
                || commands.get(AgentDeviceCodes.EXHAUST_FAN).isTargetOn()) {
            commands.put(AgentDeviceCodes.CO2_SUPPLY, command(AgentDeviceCodes.CO2_SUPPLY, false,
                    "CO2_BLOCKED_BY_VENTILATION", 2, "通风期间禁止自动 CO2 补给", null, true));
        }

        String summary = reasons.isEmpty() ? "环境处于目标区间，维持当前自动策略" : join(reasons);
        List<DeviceCommand> scaled = new ArrayList<>();
        for (DeviceCommand proposed : commands.values()) {
            BigDecimal amount = proposed.getResourceCode() == null ? null
                    : resourceAmount(proposed.getDeviceCode(), tickMinutes);
            scaled.add(new DeviceCommand(proposed.getDeviceCode(), proposed.isTargetOn(),
                    proposed.getRuleCode(), proposed.getPriority(), proposed.getSummary(),
                    proposed.getResourceCode(), amount, proposed.isUrgent()));
        }
        return new DecisionPlan(scaled, summary, state.getRiskLevel());
    }

    /** 处方使用水 m³ / CO₂ kg / 能源 kWh；与评测共用参考定额，按实际步长折算。 */
    private BigDecimal resourceAmount(String code, int minutes) {
        double rate;
        if (AgentDeviceCodes.IRRIGATION.equals(code)) rate = ResourceRates.IRRIGATION_M3_PER_STEP;
        else if (AgentDeviceCodes.CO2_SUPPLY.equals(code)) rate = ResourceRates.CO2_KG_PER_STEP;
        else if (AgentDeviceCodes.VENTILATION.equals(code)) rate = ResourceRates.VENTILATION_KWH_PER_STEP;
        else if (AgentDeviceCodes.GROW_LIGHT.equals(code)) rate = ResourceRates.GROW_LIGHT_KWH_PER_STEP;
        else if (AgentDeviceCodes.SHADE.equals(code)) rate = ResourceRates.SHADE_KWH_PER_STEP;
        else if (AgentDeviceCodes.ROOF_VENT.equals(code)) rate = ResourceRates.ROOF_VENT_KWH_PER_STEP;
        else if (AgentDeviceCodes.EXHAUST_FAN.equals(code)) rate = ResourceRates.EXHAUST_FAN_KWH_PER_STEP;
        else if (AgentDeviceCodes.CIRCULATION_FAN.equals(code)) rate = ResourceRates.CIRCULATION_FAN_KWH_PER_STEP;
        else if (AgentDeviceCodes.HEATING.equals(code)) rate = ResourceRates.HEATING_KWH_PER_STEP;
        // 湿帘补水由温湿度决定，不给固定耗水处方。
        else return null;
        return BigDecimal.valueOf(rate * Math.max(1, minutes) / ResourceRates.REFERENCE_MINUTES)
                .setScale(3, java.math.RoundingMode.HALF_UP);
    }

    private DeviceCommand idleCommand(String code) {
        return command(code, false, "AUTO_IDLE", 90, "无触发规则，设备待机", null, false);
    }

    private DeviceCommand command(String code, boolean targetOn, String ruleCode, int priority,
                                  String summary, String resourceCode, boolean urgent) {
        return new DeviceCommand(code, targetOn, ruleCode, priority, summary, resourceCode,
                null, urgent);
    }

    private String join(List<String> values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (builder.length() > 0) {
                builder.append("；");
            }
            builder.append(value);
        }
        return builder.toString();
    }
}
