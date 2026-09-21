package com.example.Ece.agent.engine;

import com.example.Ece.agent.model.AgentDeviceCodes;
import com.example.Ece.agent.model.DecisionPlan;
import com.example.Ece.agent.model.DeviceCommand;
import com.example.Ece.agent.model.SimulationState;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TomatoDecisionPolicyTest {

    private final TomatoSimulationEngine engine = new TomatoSimulationEngine();
    private final TomatoDecisionPolicy policy = new TomatoDecisionPolicy();

    @Test
    void highTemperatureAndDrySoilPrioritizeVentilationAndIrrigation() {
        SimulationState state = engine.evaluate(LocalDateTime.of(2026, 9, 21, 14, 0),
                31.0, 66.0, 40.0, 760.0, 920.0, 6.5);

        DecisionPlan plan = policy.decide(state);

        assertTrue(command(plan, AgentDeviceCodes.VENTILATION).isTargetOn());
        assertTrue(command(plan, AgentDeviceCodes.IRRIGATION).isTargetOn());
        assertTrue(command(plan, AgentDeviceCodes.SHADE).isTargetOn());
    }

    @Test
    void ventilationBlocksAutomaticCo2SupplyEvenWhenCo2IsLow() {
        SimulationState state = engine.evaluate(LocalDateTime.of(2026, 9, 21, 14, 0),
                31.0, 85.0, 55.0, 580.0, 700.0, 6.5);

        DecisionPlan plan = policy.decide(state);

        assertTrue(command(plan, AgentDeviceCodes.VENTILATION).isTargetOn());
        assertFalse(command(plan, AgentDeviceCodes.CO2_SUPPLY).isTargetOn());
        assertTrue(command(plan, AgentDeviceCodes.CO2_SUPPLY).getRuleCode().contains("BLOCKED"));
    }

    @Test
    void lowCo2WithoutVentilationRequestsCo2Supply() {
        SimulationState state = engine.evaluate(LocalDateTime.of(2026, 9, 21, 9, 0),
                24.0, 68.0, 55.0, 550.0, 520.0, 6.5);

        DecisionPlan plan = policy.decide(state);

        assertFalse(command(plan, AgentDeviceCodes.VENTILATION).isTargetOn());
        assertTrue(command(plan, AgentDeviceCodes.CO2_SUPPLY).isTargetOn());
    }

    private DeviceCommand command(DecisionPlan plan, String code) {
        for (DeviceCommand command : plan.getCommands()) {
            if (code.equals(command.getDeviceCode())) return command;
        }
        throw new AssertionError("missing device command: " + code);
    }
}
