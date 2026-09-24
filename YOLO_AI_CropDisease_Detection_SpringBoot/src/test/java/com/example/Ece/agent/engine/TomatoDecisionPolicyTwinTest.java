package com.example.Ece.agent.engine;

import com.example.Ece.agent.model.AgentDeviceCodes;
import com.example.Ece.agent.model.DecisionPlan;
import com.example.Ece.agent.model.DeviceCommand;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TomatoDecisionPolicyTwinTest {
    private final TomatoSimulationEngine engine = new TomatoSimulationEngine();
    private final TomatoDecisionPolicy policy = new TomatoDecisionPolicy();

    @Test
    void hotDryAirEnablesExhaustAndPadButDisablesCo2() {
        DecisionPlan plan = policy.decide(engine.evaluate(LocalDateTime.of(2026, 9, 21, 14, 0),
                35.0, 55.0, 52.0, 500.0, 800.0, 6.5));
        assertTrue(on(plan, AgentDeviceCodes.EXHAUST_FAN));
        assertTrue(on(plan, AgentDeviceCodes.COOLING_PAD));
        assertTrue(on(plan, AgentDeviceCodes.ROOF_VENT));
        assertFalse(on(plan, AgentDeviceCodes.CO2_SUPPLY));
    }

    @Test
    void saturatedAirNeverAddsPadWater() {
        DecisionPlan plan = policy.decide(engine.evaluate(LocalDateTime.of(2026, 9, 21, 14, 0),
                35.0, 92.0, 52.0, 500.0, 800.0, 6.5));
        assertTrue(on(plan, AgentDeviceCodes.EXHAUST_FAN));
        assertFalse(on(plan, AgentDeviceCodes.COOLING_PAD));
    }

    private boolean on(DecisionPlan plan, String code) {
        for (DeviceCommand command : plan.getCommands()) {
            if (code.equals(command.getDeviceCode())) return command.isTargetOn();
        }
        throw new AssertionError("Missing command for " + code);
    }
}
