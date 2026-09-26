package com.example.Ece.agent.engine;

import com.example.Ece.agent.model.AgentDeviceCodes;
import com.example.Ece.agent.model.DeviceCommand;
import com.example.Ece.agent.model.SimulationState;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 连续控制器测试。核心是两条：积分项确实能持续加大出力（这是它消除稳态偏差的机理），
 * 以及控制器在任何工况下都不破坏设备互斥约束与出力范围。
 */
class PidControlPolicyTest {

    private final TomatoSimulationEngine engine = new TomatoSimulationEngine();

    /** 构造一个温度略高于设定值、其余回路误差为零的状态：误差 = (26.2 − 26.0) / 4 = 0.05。 */
    private SimulationState slightHeat() {
        return engine.evaluate(LocalDateTime.of(2026, 9, 21, 14, 0),
                26.2, 70.0, 60.0, PidControlPolicy.CO2_SET_PPM, PidControlPolicy.PPFD_SET, 6.5);
    }

    @Test
    void proportionalOnlyHoldsAFixedOutputWhileIntegralKeepsIncreasingIt() {
        SimulationState state = slightHeat();
        PidControlPolicy proportional = PidControlPolicy.proportionalOnly();
        PidControlPolicy integral = PidControlPolicy.proportionalIntegral();

        double proportionalFirst = 0.0;
        double proportionalLast = 0.0;
        double integralFirst = 0.0;
        double integralLast = 0.0;
        for (int step = 0; step < 60; step++) {
            double p = proportional.decide(state).getDuties().get(AgentDeviceCodes.VENTILATION).doubleValue();
            double i = integral.decide(state).getDuties().get(AgentDeviceCodes.VENTILATION).doubleValue();
            if (step == 0) {
                proportionalFirst = p;
                integralFirst = i;
            }
            proportionalLast = p;
            integralLast = i;
        }

        // 纯比例档：恒定误差下出力恒定，永远停在"够不到设定值"的那个开度上——这正是稳态偏差。
        assertEquals(proportionalFirst, proportionalLast, 1e-12);
        // 积分档：只要误差非零就持续加大出力，因此出力随步数单调增长。
        assertTrue(integralLast > integralFirst,
                "积分档出力应随持续误差增长，实测 first=" + integralFirst + " last=" + integralLast);
        assertTrue(integralLast > proportionalLast,
                "积分档最终出力应超过纯比例档，实测 integral=" + integralLast + " proportional=" + proportionalLast);
    }

    @Test
    void derivativeTermDampsTheApproachToTheSetpoint() {
        SimulationState state = slightHeat();
        PidControlPolicy pi = PidControlPolicy.proportionalIntegral();
        PidControlPolicy pid = PidControlPolicy.proportionalIntegralDerivative();

        // 制造一个正在快速逼近设定值的误差序列：误差持续减小，微分项应压低出力、抑制超调。
        double piOutput = 0.0;
        double pidOutput = 0.0;
        for (int step = 0; step < 8; step++) {
            double decayingTemperature = 30.0 - step * 0.5;
            SimulationState decaying = engine.evaluate(state.getSimulatedAt(), decayingTemperature,
                    70.0, 60.0, PidControlPolicy.CO2_SET_PPM, PidControlPolicy.PPFD_SET, 6.5);
            piOutput = pi.decide(decaying).getDuties().get(AgentDeviceCodes.VENTILATION).doubleValue();
            pidOutput = pid.decide(decaying).getDuties().get(AgentDeviceCodes.VENTILATION).doubleValue();
        }

        assertTrue(pidOutput < piOutput,
                "误差快速回落时微分项应压低出力以防超调，实测 pid=" + pidOutput + " pi=" + piOutput);
    }

    @Test
    void resetClearsTheIntegralMemory() {
        SimulationState state = slightHeat();
        PidControlPolicy policy = PidControlPolicy.proportionalIntegral();
        for (int step = 0; step < 40; step++) {
            policy.decide(state);
        }
        double charged = policy.decide(state).getDuties().get(AgentDeviceCodes.VENTILATION).doubleValue();
        policy.reset();
        double afterReset = policy.decide(state).getDuties().get(AgentDeviceCodes.VENTILATION).doubleValue();
        double fresh = PidControlPolicy.proportionalIntegral().decide(state)
                .getDuties().get(AgentDeviceCodes.VENTILATION).doubleValue();

        assertTrue(charged > afterReset, "重置后积分记忆应被清空");
        assertEquals(fresh, afterReset, 1e-12, "重置后的首步出力应与全新控制器一致");
    }

    @Test
    void repeatedRunsProduceIdenticalDutySequences() {
        SimulationState state = slightHeat();
        PidControlPolicy first = PidControlPolicy.proportionalIntegralDerivative();
        PidControlPolicy second = PidControlPolicy.proportionalIntegralDerivative();
        for (int step = 0; step < 30; step++) {
            Map<String, Double> left = first.decide(state).getDuties();
            Map<String, Double> right = second.decide(state).getDuties();
            for (String code : AgentDeviceCodes.all()) {
                assertEquals(left.get(code).doubleValue(), right.get(code).doubleValue(),
                        "设备 " + code + " 在第 " + step + " 步出力不一致");
            }
        }
    }

    @Test
    void dutiesStayInRangeAndNeverViolateDeviceExclusions() {
        PidControlPolicy policy = PidControlPolicy.proportionalIntegralDerivative();
        double[] temperatures = {5.0, 15.0, 24.0, 28.0, 33.0, 45.0};
        double[] humidities = {25.0, 60.0, 78.0, 90.0, 99.0};
        double[] co2Levels = {250.0, 600.0, 800.0, 1400.0};
        double[] lightLevels = {0.0, 300.0, 700.0, 1600.0};

        for (double temperature : temperatures) {
            for (double humidity : humidities) {
                for (double co2 : co2Levels) {
                    for (double light : lightLevels) {
                        policy.reset();
                        SimulationState state = engine.evaluate(LocalDateTime.of(2026, 9, 21, 12, 0),
                                temperature, humidity, 60.0, co2, light, 6.5);
                        Map<String, Double> duties = policy.decide(state).getDuties();
                        for (String code : AgentDeviceCodes.all()) {
                            double duty = duties.get(code).doubleValue();
                            assertTrue(duty >= 0.0 && duty <= 1.0, code + " 出力越界：" + duty);
                        }
                        boolean airExchanging = duties.get(AgentDeviceCodes.VENTILATION).doubleValue() > 0.0
                                || duties.get(AgentDeviceCodes.ROOF_VENT).doubleValue() > 0.0
                                || duties.get(AgentDeviceCodes.EXHAUST_FAN).doubleValue() > 0.0;
                        assertTrue(!airExchanging || duties.get(AgentDeviceCodes.CO2_SUPPLY).doubleValue() == 0.0,
                                "换气与 CO₂ 补给同时出力，温度 " + temperature + " 湿度 " + humidity);
                        boolean shadeOn = duties.get(AgentDeviceCodes.SHADE).doubleValue() > 0.0;
                        assertTrue(!shadeOn || duties.get(AgentDeviceCodes.GROW_LIGHT).doubleValue() == 0.0,
                                "遮阳与补光同时出力，温度 " + temperature + " 光照 " + light);
                    }
                }
            }
        }
    }

    @Test
    void coolingPadOnlyRunsWithExhaustAndOnlyWhenAirIsNotAlreadyHumid() {
        PidControlPolicy policy = PidControlPolicy.proportionalIntegral();
        // 需要足够高的温度才能把换气需求推到湿帘分档（≥0.85）。
        SimulationState hotDry = engine.evaluate(LocalDateTime.of(2026, 9, 21, 14, 0),
                38.0, 50.0, 60.0, PidControlPolicy.CO2_SET_PPM, 900.0, 6.5);
        SimulationState hotHumid = engine.evaluate(LocalDateTime.of(2026, 9, 21, 14, 0),
                38.0, 90.0, 60.0, PidControlPolicy.CO2_SET_PPM, 900.0, 6.5);

        Map<String, Double> dry = policy.decide(hotDry).getDuties();
        policy.reset();
        Map<String, Double> humid = policy.decide(hotHumid).getDuties();

        assertTrue(dry.get(AgentDeviceCodes.EXHAUST_FAN).doubleValue() > 0.0, "高温应先开强制排风");
        assertTrue(dry.get(AgentDeviceCodes.COOLING_PAD).doubleValue() > 0.0, "干热工况应启用湿帘");
        assertEquals(0.0, humid.get(AgentDeviceCodes.COOLING_PAD).doubleValue(),
                "高湿工况不得启用湿帘，否则蒸发降温会进一步推高湿度");
    }

    @Test
    void planKeepsDeviceCommandsConsistentWithDuties() {
        SimulationState state = engine.evaluate(LocalDateTime.of(2026, 9, 21, 14, 0),
                33.0, 88.0, 60.0, 400.0, 300.0, 6.5);
        PidControlPolicy.Control control = PidControlPolicy.proportionalIntegralDerivative().decide(state);

        for (DeviceCommand command : control.getPlan().getCommands()) {
            double duty = control.getDuties().get(command.getDeviceCode()).doubleValue();
            assertEquals(duty > 0.0, command.isTargetOn(),
                    command.getDeviceCode() + " 的开关与出力不一致");
        }
        assertFalse(control.getPlan().getSummary().isEmpty(), "决策摘要不应为空");
    }
}
