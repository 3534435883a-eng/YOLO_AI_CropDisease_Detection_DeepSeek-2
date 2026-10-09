package com.example.Ece.agent.m3.scenario;

import com.example.Ece.agent.crop.TomatoCropGrowthModel;
import com.example.Ece.agent.crop.TomatoCropState;
import com.example.Ece.agent.model.SimulationState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScenarioAgronomyModelTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private ObjectNode environment(double temperature, double humidity, double ppfd) {
        ObjectNode e = mapper.createObjectNode();
        e.put("temperatureC", temperature); e.put("airHumidityPct", humidity); e.put("ppfd", ppfd);
        return e;
    }

    private Map<String, Double> duty(String code, double value) {
        Map<String, Double> d = new LinkedHashMap<>(); d.put(code, value); return d;
    }

    @Test
    void usesFaoSaturationVapourPressureUnitsForInstantaneousVpd() {
        ScenarioAgronomyModel m = new ScenarioAgronomyModel(mapper);
        m.advance(environment(24.5, 70, 400), Collections.emptyMap(), 30);
        assertEquals(3.075 * .30, m.snapshot().path("vpdKpa").asDouble(), .0002);
        m.advance(environment(24.5, 100, 400), Collections.emptyMap(), 1);
        assertEquals(0, m.snapshot().path("vpdKpa").asDouble(), 1e-12);
    }

    @Test
    void conservesWaterWithIrrigationEtDrainageAndSaturationOverflow() {
        ScenarioAgronomyModel m = new ScenarioAgronomyModel(mapper);
        for (int i = 0; i < 240; i++) {
            m.advance(environment(24, 70, 800), duty("IRRIGATION", 1), 30);
            ObjectNode s = m.snapshot();
            assertEquals(0, s.path("waterBalanceResidualL").asDouble(), 1e-6);
            assertTrue(s.path("rootWaterL").asDouble() >= 0);
            assertTrue(s.path("soilMoistureVwcPct").asDouble() <= s.path("saturationVwcPct").asDouble());
        }
        assertTrue(m.snapshot().path("drainageL").asDouble() > 0);
        assertEquals(240 * 30 * 15, m.resources().path("irrigationWaterL").asDouble(), 1e-8);
    }

    @Test
    void constantInputsGiveSameWaterAndExposureAcrossOneFifteenAndThirtyMinuteCalls() {
        ScenarioAgronomyModel one = new ScenarioAgronomyModel(mapper);
        ScenarioAgronomyModel fifteen = new ScenarioAgronomyModel(mapper);
        ScenarioAgronomyModel thirty = new ScenarioAgronomyModel(mapper);
        ObjectNode e = environment(22, 98, 500);
        Map<String, Double> d = duty("IRRIGATION", .7);
        for (int i = 0; i < 1440; i++) one.advance(e, d, 1);
        for (int i = 0; i < 96; i++) fifteen.advance(e, d, 15);
        for (int i = 0; i < 48; i++) thirty.advance(e, d, 30);
        String[] metrics = {"rootWaterL", "waterUsedL", "drainageL", "evapotranspirationL", "canopyWetnessProxy",
            "canopyWetMinutes", "wetExposureMinutes", "dryExposureMinutes", "continuousWetMinutes"};
        for (String metric : metrics) {
            assertEquals(one.snapshot().path(metric).asDouble(), fifteen.snapshot().path(metric).asDouble(), 1e-8, metric);
            assertEquals(one.snapshot().path(metric).asDouble(), thirty.snapshot().path(metric).asDouble(), 1e-8, metric);
        }
        assertEquals(one.checkpoint().path("coolDiseaseMinutes"), thirty.checkpoint().path("coolDiseaseMinutes"));
    }

    @Test
    void onlyCountsMinutesAfterCanopyProxyBecomesWet() {
        ScenarioAgronomyModel m = new ScenarioAgronomyModel(mapper);
        m.advance(environment(22, 98, 0), Collections.emptyMap(), 30);
        assertEquals(0, m.snapshot().path("canopyWetMinutes").asDouble(), 1e-9);
        m.advance(environment(22, 98, 0), Collections.emptyMap(), 30);
        double wetMinutes = m.snapshot().path("canopyWetMinutes").asDouble();
        assertTrue(wetMinutes > 0 && wetMinutes < 30, "Threshold crossing must not count the whole 30-minute segment");
    }

    @Test
    void resourceAccountUsesSameFullIntervalAndSeparatesIrrigationFromCoolingWater() {
        ScenarioAgronomyModel m = new ScenarioAgronomyModel(mapper);
        Map<String, Double> d = duty("IRRIGATION", .5);
        d.put("COOLING_PAD", .25); d.put("CO2_SUPPLY", .4); d.put("HEATING", .5);
        m.advance(environment(22, 72, 400), d, 30);
        ObjectNode r = m.resources();
        assertEquals(225, r.path("irrigationWaterL").asDouble(), 1e-9);
        assertEquals(15, r.path("coolingWaterL").asDouble(), 1e-9);
        assertEquals(240, r.path("totalWaterL").asDouble(), 1e-9);
        assertEquals(.3, r.path("co2Kg").asDouble(), 1e-9);
        assertEquals(10 + .075 + .09375 + .01, r.path("electricityKwh").asDouble(), 1e-9);
        assertEquals(225, r.path("lastStep").path("irrigationWaterL").asDouble(), 1e-9);
        assertTrue(r.path("lastStep").path("evapotranspirationL").asDouble() > 1);
    }

    @Test
    void circulationChangesWetnessProxyButNotRootWaterOrClaimedAirHumidity() {
        ScenarioAgronomyModel still = new ScenarioAgronomyModel(mapper), fan = new ScenarioAgronomyModel(mapper);
        ObjectNode e = environment(22, 98, 100);
        still.advance(e, Collections.emptyMap(), 360); fan.advance(e, duty("CIRCULATION_FAN", 1), 360);
        assertTrue(fan.snapshot().path("canopyWetnessProxy").asDouble() < still.snapshot().path("canopyWetnessProxy").asDouble());
        assertEquals(still.snapshot().path("rootWaterL"), fan.snapshot().path("rootWaterL"));
        assertFalse(fan.snapshot().path("diseaseConditions").get(0).path("diagnosis").asBoolean());
        assertFalse(fan.snapshot().path("diseaseConditions").get(0).path("validatedProbability").asBoolean());
    }

    @Test
    void checkpointRestoresTrajectoryAndResourceLedger() {
        ScenarioAgronomyModel uninterrupted = new ScenarioAgronomyModel(mapper), restored = new ScenarioAgronomyModel(mapper);
        ObjectNode e = environment(22, 98, 100); Map<String, Double> d = duty("IRRIGATION", .4);
        uninterrupted.advance(e, d, 330); restored.restoreCheckpoint(uninterrupted.checkpoint());
        assertEquals(uninterrupted.checkpoint(), restored.checkpoint());
        uninterrupted.advance(e, d, 30); restored.advance(e, d, 30);
        assertEquals(uninterrupted.checkpoint(), restored.checkpoint());
    }

    @Test
    void rejectsNonConservingOrInconsistentResourceCheckpoint() {
        ScenarioAgronomyModel m = new ScenarioAgronomyModel(mapper);
        m.advance(environment(22, 72, 400), duty("IRRIGATION", .3), 30);
        ObjectNode water = m.checkpoint(); water.put("rootWaterL", water.path("rootWaterL").asDouble() + 1);
        assertThrows(IllegalArgumentException.class, () -> new ScenarioAgronomyModel(mapper).restoreCheckpoint(water));
        ObjectNode electricity = m.checkpoint();
        ((ObjectNode)electricity.path("resources")).put("electricityKwh", 99);
        assertThrows(IllegalArgumentException.class, () -> new ScenarioAgronomyModel(mapper).restoreCheckpoint(electricity));
    }

    @Test
    void parameterRegistryKeepsEngineeringValuesAndScopedReferencesExplicit() {
        ScenarioAgronomyModel m = new ScenarioAgronomyModel(mapper);
        ArrayNode parameters = mapper.createArrayNode(); m.appendParameters(parameters);
        assertTrue(parameters.size() > 40);
        parameters.forEach(p -> {
            assertFalse(p.path("calibrated").asBoolean());
            assertFalse(p.path("unit").asText().isEmpty());
            assertFalse(p.path("scope").asText().isEmpty());
            assertNotEquals("M3_MEASURED", p.path("source").asText());
        });
        assertTrue(m.snapshot().path("rootDepthMeaning").asText().contains("非番茄最大根深"));
    }

    @Test
    void rejectsNonFiniteDutyBeforeMutatingWaterState() {
        ScenarioAgronomyModel m = new ScenarioAgronomyModel(mapper);
        double initialWater = m.snapshot().path("rootWaterL").asDouble();
        assertThrows(IllegalArgumentException.class, () -> m.advance(environment(22, 72, 400), duty("HEATING", Double.NaN), 30));
        assertEquals(initialWater, m.snapshot().path("rootWaterL").asDouble(), 0);
        assertEquals(0, m.resources().path("totalWaterL").asDouble(), 0);
    }

    @Test
    void noAvailableRootWaterStopsGrowthContributionWithoutInventingNegativeWater() {
        ScenarioAgronomyModel m = new ScenarioAgronomyModel(mapper);
        m.advance(environment(35, 20, 1800), Collections.emptyMap(), 50 * 1440);
        assertTrue(m.snapshot().path("soilMoistureVwcPct").asDouble() <= 12);
        assertEquals(0, m.waterStressFactor(), 0);
        assertEquals(0, m.snapshot().path("waterBalanceResidualL").asDouble(), 1e-6);
        assertTrue(m.snapshot().path("rootWaterL").asDouble() >= 0);
        TomatoCropGrowthModel crop = new TomatoCropGrowthModel(); TomatoCropState before = crop.initial();
        SimulationState e = new SimulationState(LocalDateTime.of(2025, 4, 19, 12, 0), 24, 70, 60, 600, 500, 6.5, .9, 0, 0, "SCENARIO");
        TomatoCropState after = crop.advanceCalibrated(before, e, 30, .2, m.waterStressFactor());
        assertTrue(after.getWTotal() <= before.getWTotal() + 1e-9, "Zero external root factor must not accumulate dry matter");
        assertEquals(before.getPlantHeightCm(), after.getPlantHeightCm(), 1e-9);
    }
}
