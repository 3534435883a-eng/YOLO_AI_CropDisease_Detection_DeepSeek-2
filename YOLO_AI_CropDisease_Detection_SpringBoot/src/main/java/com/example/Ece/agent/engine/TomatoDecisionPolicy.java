package com.example.Ece.agent.engine;

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
        Map<String, DeviceCommand> commands = new LinkedHashMap<>();
        for (String code : AgentDeviceCodes.all()) {
            commands.put(code, idleCommand(code));
        }

        List<String> reasons = new ArrayList<>();
        boolean urgent = "HIGH".equals(state.getRiskLevel());
        if (state.getTemperatureC() >= 29.0 || state.getAirHumidityPct() >= 82.0) {
            String summary = state.getTemperatureC() >= 29.0 ? "温度偏高，优先通风降温" : "空气湿度偏高，优先通风排湿";
            commands.put(AgentDeviceCodes.VENTILATION, command(AgentDeviceCodes.VENTILATION, true,
                    "VENTILATE", 1, summary, "ENERGY", "0.350", urgent));
            reasons.add(summary);
        }
        if (state.getTemperatureC() >= 30.0 || state.getLightPpfd() >= 900.0) {
            commands.put(AgentDeviceCodes.SHADE, command(AgentDeviceCodes.SHADE, true,
                    "SHADE_HEAT", 2, "高温或强光，启用遮阳降低热负荷", "ENERGY", "0.100", urgent));
            reasons.add("遮阳降低热负荷");
        }
        if (state.getSoilMoisturePct() <= 45.0) {
            commands.put(AgentDeviceCodes.IRRIGATION, command(AgentDeviceCodes.IRRIGATION, true,
                    "IRRIGATE_DRY", 1, "土壤水分偏低，执行小水滴灌", "WATER", "0.600", urgent));
            reasons.add("补充土壤水分");
        }
        if (state.getLightPpfd() <= 280.0 && !commands.get(AgentDeviceCodes.SHADE).isTargetOn()) {
            commands.put(AgentDeviceCodes.GROW_LIGHT, command(AgentDeviceCodes.GROW_LIGHT, true,
                    "SUPPLEMENT_LIGHT", 3, "有效光照不足，开启补光", "ENERGY", "1.200", false));
            reasons.add("补充有效光照");
        }
        if (state.getCo2Ppm() <= 650.0 && !commands.get(AgentDeviceCodes.VENTILATION).isTargetOn()) {
            commands.put(AgentDeviceCodes.CO2_SUPPLY, command(AgentDeviceCodes.CO2_SUPPLY, true,
                    "SUPPLY_CO2", 4, "CO2 浓度偏低，补充 CO2", "CO2", "0.080", false));
            reasons.add("补充 CO2");
        }

        // Ventilation always wins over CO2 supply in automatic mode.
        if (commands.get(AgentDeviceCodes.VENTILATION).isTargetOn()) {
            commands.put(AgentDeviceCodes.CO2_SUPPLY, command(AgentDeviceCodes.CO2_SUPPLY, false,
                    "CO2_BLOCKED_BY_VENTILATION", 2, "通风期间禁止自动 CO2 补给", null, null, true));
        }

        String summary = reasons.isEmpty() ? "环境处于目标区间，维持当前自动策略" : join(reasons);
        return new DecisionPlan(new ArrayList<>(commands.values()), summary, state.getRiskLevel());
    }

    private DeviceCommand idleCommand(String code) {
        return command(code, false, "AUTO_IDLE", 90, "无触发规则，设备待机", null, null, false);
    }

    private DeviceCommand command(String code, boolean targetOn, String ruleCode, int priority,
                                  String summary, String resourceCode, String resourceAmount, boolean urgent) {
        return new DeviceCommand(code, targetOn, ruleCode, priority, summary, resourceCode,
                resourceAmount == null ? null : new BigDecimal(resourceAmount), urgent);
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
