package com.example.Ece.agent.service;

import com.example.Ece.agent.model.AgentDeviceCodes;
import com.example.Ece.agent.model.DecisionPlan;
import com.example.Ece.agent.model.DeviceCommand;
import com.example.Ece.agent.model.SimulationState;
import com.example.Ece.agent.rag.ScoredChunk;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 把规则引擎的决策结果翻译成"农事处方"：做什么、用多少、注意什么。
 * 处方只描述建议与依据，**不触发任何设备动作**；改动设备一律标记需人工确认。
 */
@Component
public class PrescriptionService {

    public Prescription draft(SimulationState state, DecisionPlan plan, List<ScoredChunk> citations) {
        List<String> actions = new ArrayList<String>();
        List<String> cautions = new ArrayList<String>();
        if (plan == null) {
            return new Prescription("暂无可用决策结果。", actions, cautions, true);
        }
        for (DeviceCommand command : plan.getCommands()) {
            if (!command.isTargetOn()) {
                continue;
            }
            actions.add(describe(command));
            if (command.isUrgent()) {
                cautions.add("紧急项：" + command.getSummary() + "，建议尽快人工确认后执行。");
            }
        }
        if ("HIGH".equalsIgnoreCase(plan.getRiskLevel())) {
            cautions.add("当前环境风险等级为高，执行前请人工复核环境数据与作物状态。");
        }
        if (citations == null || citations.isEmpty()) {
            cautions.add("本处方未附带知识库引用，建议人工核对当地技术意见后再执行。");
        }
        if (actions.isEmpty()) {
            cautions.add("当前无需调整设备，保持现有管理即可。");
        }
        String conclusion = plan.getSummary();
        if (state != null) {
            conclusion = conclusion + String.format("（当前 %.1f℃ / 湿度 %.0f%% / 土壤 %.0f%%）",
                    state.getTemperatureC(), state.getAirHumidityPct(), state.getSoilMoisturePct());
        }
        // 任何设备动作都需人工确认；全部动作都为"待机"时无需确认
        boolean requiresManualConfirm = !actions.isEmpty();
        return new Prescription(conclusion, actions, cautions, requiresManualConfirm);
    }

    private String describe(DeviceCommand command) {
        StringBuilder builder = new StringBuilder();
        builder.append(deviceLabel(command.getDeviceCode()));
        builder.append("（规则 ").append(command.getRuleCode()).append("）");
        if (command.getResourceCode() != null) {
            builder.append("，预计消耗 ").append(formatAmount(command.getResourceAmount()))
                    .append(" ").append(unitLabel(command.getResourceCode()));
        }
        return builder.toString();
    }

    private String formatAmount(BigDecimal amount) {
        return amount == null ? "-" : amount.stripTrailingZeros().toPlainString();
    }

    private String unitLabel(String resourceCode) {
        if ("WATER".equals(resourceCode)) {
            return "m³";
        }
        if ("CO2".equals(resourceCode)) {
            return "kg";
        }
        if ("ENERGY".equals(resourceCode)) {
            return "kWh";
        }
        return resourceCode;
    }

    private String deviceLabel(String deviceCode) {
        if (AgentDeviceCodes.IRRIGATION.equals(deviceCode)) {
            return "开启灌溉";
        }
        if (AgentDeviceCodes.VENTILATION.equals(deviceCode)) {
            return "开启通风";
        }
        if (AgentDeviceCodes.GROW_LIGHT.equals(deviceCode)) {
            return "开启补光";
        }
        if (AgentDeviceCodes.SHADE.equals(deviceCode)) {
            return "启用遮阳";
        }
        if (AgentDeviceCodes.CO2_SUPPLY.equals(deviceCode)) {
            return "开启 CO₂ 补给";
        }
        return "调整设备 " + deviceCode;
    }
}
