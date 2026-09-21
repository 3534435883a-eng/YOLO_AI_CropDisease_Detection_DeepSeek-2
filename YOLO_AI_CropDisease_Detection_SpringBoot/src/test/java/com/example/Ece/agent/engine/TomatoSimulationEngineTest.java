package com.example.Ece.agent.engine;

import com.example.Ece.agent.model.AgentDeviceCodes;
import com.example.Ece.agent.model.SimulationState;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TomatoSimulationEngineTest {

    private final TomatoSimulationEngine engine = new TomatoSimulationEngine();

    @Test
    void producesTheSameProjectionForTheSameSeedAndDeviceState() {
        SimulationState state = engine.evaluate(LocalDateTime.of(2026, 9, 21, 9, 0),
                30.0, 68.0, 42.0, 620.0, 220.0, 6.5);
        Map<String, Boolean> devices = new HashMap<>();
        devices.put(AgentDeviceCodes.IRRIGATION, true);
        devices.put(AgentDeviceCodes.VENTILATION, true);

        SimulationState first = engine.advance(state, devices, 15, 77L);
        SimulationState second = engine.advance(state, devices, 15, 77L);

        assertEquals(first.getTemperatureC(), second.getTemperatureC());
        assertEquals(first.getAirHumidityPct(), second.getAirHumidityPct());
        assertEquals(first.getSoilMoisturePct(), second.getSoilMoisturePct());
        assertEquals(first.getCo2Ppm(), second.getCo2Ppm());
        assertEquals(first.getEnvironmentRisk(), second.getEnvironmentRisk());
    }

    @Test
    void ventilationReducesTemperatureAndHumidityAgainstTheHoldProjection() {
        SimulationState state = engine.evaluate(LocalDateTime.of(2026, 9, 21, 14, 0),
                32.0, 88.0, 55.0, 800.0, 900.0, 6.5);
        SimulationState hold = engine.advance(state, new HashMap<String, Boolean>(), 15, 42L);
        Map<String, Boolean> devices = new HashMap<>();
        devices.put(AgentDeviceCodes.VENTILATION, true);
        SimulationState ventilated = engine.advance(state, devices, 15, 42L);

        assertTrue(ventilated.getTemperatureC() < hold.getTemperatureC());
        assertTrue(ventilated.getAirHumidityPct() < hold.getAirHumidityPct());
    }

    @Test
    void highHumidityAndLowLightRaiseDiseasePressure() {
        SimulationState benign = engine.evaluate(LocalDateTime.of(2026, 9, 21, 10, 0),
                23.0, 65.0, 58.0, 800.0, 620.0, 6.5);
        SimulationState humidAndDark = engine.evaluate(LocalDateTime.of(2026, 9, 21, 10, 0),
                23.0, 90.0, 82.0, 800.0, 130.0, 6.5);

        assertTrue(humidAndDark.getDiseasePressure() > benign.getDiseasePressure());
        assertTrue(humidAndDark.getEnvironmentRisk() > benign.getEnvironmentRisk());
    }
}
