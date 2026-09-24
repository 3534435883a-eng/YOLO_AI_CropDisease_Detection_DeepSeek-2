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

    @Test
    void padNeedsExhaustAndHumidifiesWhileCooling() {
        SimulationState state = engine.evaluate(LocalDateTime.of(2026, 9, 21, 14, 0),
                35.0, 50.0, 55.0, 800.0, 850.0, 6.5);
        Map<String, Boolean> exhaust = new HashMap<>();
        exhaust.put(AgentDeviceCodes.EXHAUST_FAN, true);
        Map<String, Boolean> padOnly = new HashMap<>();
        padOnly.put(AgentDeviceCodes.COOLING_PAD, true);
        Map<String, Boolean> both = new HashMap<>(exhaust);
        both.put(AgentDeviceCodes.COOLING_PAD, true);

        SimulationState withoutPad = engine.advance(state, exhaust, 15, 42L);
        SimulationState withoutExhaust = engine.advance(state, padOnly, 15, 42L);
        SimulationState combined = engine.advance(state, both, 15, 42L);

        assertEquals(engine.advance(state, new HashMap<String, Boolean>(), 15, 42L).getTemperatureC(),
                withoutExhaust.getTemperatureC());
        assertTrue(combined.getTemperatureC() < withoutPad.getTemperatureC());
        assertTrue(combined.getAirHumidityPct() > withoutPad.getAirHumidityPct());
    }

    @Test
    void outsideAirExchangePreventsCo2Accumulation() {
        SimulationState state = engine.evaluate(LocalDateTime.of(2026, 9, 21, 14, 0),
                32.0, 70.0, 55.0, 500.0, 850.0, 6.5);
        Map<String, Boolean> supply = new HashMap<>();
        supply.put(AgentDeviceCodes.CO2_SUPPLY, true);
        Map<String, Boolean> invalidCombination = new HashMap<>(supply);
        invalidCombination.put(AgentDeviceCodes.ROOF_VENT, true);
        Map<String, Boolean> roofOnly = new HashMap<>();
        roofOnly.put(AgentDeviceCodes.ROOF_VENT, true);

        assertEquals(engine.advance(state, roofOnly, 15, 42L).getCo2Ppm(),
                engine.advance(state, invalidCombination, 15, 42L).getCo2Ppm());
        assertTrue(engine.advance(state, supply, 15, 42L).getCo2Ppm()
                > engine.advance(state, invalidCombination, 15, 42L).getCo2Ppm());
    }

    @Test
    void ventilationCanWarmAColderGreenhouseRatherThanAlwaysCooling() {
        SimulationState state = engine.evaluate(LocalDateTime.of(2026, 9, 21, 14, 0),
                20.0, 65.0, 55.0, 700.0, 500.0, 6.5);
        Map<String, Boolean> ventilation = new HashMap<>();
        ventilation.put(AgentDeviceCodes.VENTILATION, true);

        assertTrue(engine.advance(state, ventilation, 15, 42L).getTemperatureC()
                > engine.advance(state, new HashMap<String, Boolean>(), 15, 42L).getTemperatureC());
    }

    @Test
    void humidOutdoorAirCanRaiseHumidityDuringExhaust() {
        SimulationState state = engine.evaluate(LocalDateTime.of(2026, 9, 21, 2, 0),
                20.0, 35.0, 55.0, 700.0, 0.0, 6.5);
        Map<String, Boolean> exhaust = new HashMap<>();
        exhaust.put(AgentDeviceCodes.EXHAUST_FAN, true);

        assertTrue(engine.advance(state, exhaust, 15, 42L).getAirHumidityPct()
                > engine.advance(state, new HashMap<String, Boolean>(), 15, 42L).getAirHumidityPct());
    }

    @Test
    void irrigationWaterMatchesTheFourBedRootZoneVolume() {
        SimulationState state = engine.evaluate(LocalDateTime.of(2026, 9, 21, 9, 0),
                25.0, 65.0, 45.0, 700.0, 500.0, 6.5);
        Map<String, Boolean> irrigation = new HashMap<>();
        irrigation.put(AgentDeviceCodes.IRRIGATION, true);

        SimulationState hold = engine.advance(state, new HashMap<String, Boolean>(), 15, 42L);
        SimulationState watered = engine.advance(state, irrigation, 15, 42L);
        double expectedPercentagePoints = 100.0 * 60.0 / (4.0 * 21.0 * 1.7 * 0.25 * 1000.0);
        assertEquals(expectedPercentagePoints, watered.getSoilMoisturePct() - hold.getSoilMoisturePct(), 0.02);
        assertTrue(state.getSoilMoisturePct() - hold.getSoilMoisturePct() < 0.05);
    }

    @Test
    void co2InjectionMatchesTheGreenhouseAirVolume() {
        SimulationState state = engine.evaluate(LocalDateTime.of(2026, 9, 21, 9, 0),
                25.0, 65.0, 55.0, 500.0, 500.0, 6.5);
        Map<String, Boolean> injection = new HashMap<>();
        injection.put(AgentDeviceCodes.CO2_SUPPLY, true);
        double addedPpm = engine.advance(state, injection, 15, 42L).getCo2Ppm()
                - engine.advance(state, new HashMap<String, Boolean>(), 15, 42L).getCo2Ppm();
        double greenhouseVolume = 26.0 * 13.0 * (4.0 + 1.7 * 2.0 / Math.PI);
        double expectedPpm = 0.250 / 0.04401 * 8.314 * (25.0 + 273.15)
                / (101325.0 * greenhouseVolume) * 1000000.0;

        assertEquals(expectedPpm, addedPpm, 0.1);
    }

    @Test
    void evaporativeWaterMakeupIsBoundedByTheInletHumidityGain() {
        SimulationState state = engine.evaluate(LocalDateTime.of(2026, 9, 21, 14, 0),
                35.0, 55.0, 50.0, 500.0, 800.0, 6.5);
        double makeup = engine.coolingPadEvaporationLiters(state, 15, 42L);
        assertTrue(makeup > 0.0);
        assertTrue(makeup < 18.0);
    }
}
