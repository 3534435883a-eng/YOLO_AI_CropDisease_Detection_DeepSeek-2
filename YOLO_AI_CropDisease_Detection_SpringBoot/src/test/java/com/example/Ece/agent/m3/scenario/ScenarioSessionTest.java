package com.example.Ece.agent.m3.scenario;

import com.example.Ece.agent.crop.TomatoCropGrowthModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScenarioSessionTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final LocalDateTime start = LocalDateTime.of(2025, 4, 19, 12, 0);

    private ObjectNode frame(int tick, double temperature) {
        ObjectNode f = mapper.createObjectNode(); f.put("at", start.plusMinutes(tick * 30L).toString()); f.put("correctedHeightCm", 100 + tick * .05);
        ObjectNode e = f.putObject("environment"); e.put("temperatureC", temperature); e.put("airHumidityPct", 72);
        e.put("co2Ppm", 600); e.put("lightRaw", 400 / (1000 * .0185)); e.put("soilMoisturePct", 62);
        return f;
    }

    private ScenarioSession session(long seed, double initialTemperature) {
        TomatoCropGrowthModel crop = new TomatoCropGrowthModel();
        ScenarioSession s = new ScenarioSession("3d8b04d8-e5d3-49be-a6c9-fcb85cbbfaaf", seed, mapper, crop, crop.initial(), .2, frame(0, initialTemperature));
        s.configure(false, false); return s;
    }

    private ObjectNode apply(ScenarioSession s, String device, double duty, int steps) {
        ObjectNode plan = mapper.createObjectNode();
        plan.putArray("actions").addObject().put("device", device).put("duty", duty).put("durationSteps", steps);
        return apply(s, plan);
    }

    private ObjectNode apply(ScenarioSession s, ObjectNode plan) {
        ObjectNode context = s.beginDecision("验证虚拟设备动作", true);
        return s.applyPlan(plan, context.path("version").asLong(), context.path("decision").path("requestId").asText(), true);
    }

    @Test
    void sameEventsWithoutActionsKeepBothBranchesIdentical() {
        ScenarioSession s = session(91, 22); s.trigger("OVERCAST");
        for (int i = 1; i <= 36; i++) {
            ObjectNode scene = s.advance(frame(i, 22));
            assertEquals(scene.path("environment"), scene.path("withoutIntervention"));
            assertEquals(scene.path("agronomy"), scene.path("shadowAgronomy"));
            assertEquals(scene.path("growth").path("plantHeightCm"), scene.path("growth").path("withoutInterventionHeightCm"));
            assertEquals(scene.path("growth").path("wTotal"), scene.path("growth").path("withoutInterventionWTotal"));
        }
    }

    @Test
    void actionRunsForExactlyRequestedNumberOfHalfHourIntervals() {
        ScenarioSession s = session(1, 22); apply(s, "IRRIGATION", .5, 2);
        assertTrue(s.advance(frame(1, 22)).path("devices").path("IRRIGATION").asBoolean());
        ObjectNode second = s.advance(frame(2, 22));
        assertTrue(second.path("devices").path("IRRIGATION").asBoolean());
        assertEquals(450, second.path("resources").path("irrigationWaterL").asDouble(), 1e-9);
        ObjectNode third = s.advance(frame(3, 22));
        assertFalse(third.path("devices").path("IRRIGATION").asBoolean());
        assertEquals(450, third.path("resources").path("irrigationWaterL").asDouble(), 1e-9);
        assertEquals(100.15, third.path("growth").path("referenceHeightCm").asDouble(), 1e-9);
    }

    @Test
    void runtimePadStopsWhenExhaustDurationExpires() {
        ScenarioSession s = session(3, 22); ObjectNode p = mapper.createObjectNode();
        p.putArray("actions").addObject().put("device", "EXHAUST_FAN").put("duty", .5).put("durationSteps", 2);
        p.withArray("actions").addObject().put("device", "COOLING_PAD").put("duty", .5).put("durationSteps", 4);
        apply(s, p); s.advance(frame(1, 22)); s.advance(frame(2, 22));
        ObjectNode scene = s.advance(frame(3, 22));
        assertFalse(scene.path("devices").path("EXHAUST_FAN").asBoolean());
        assertFalse(scene.path("devices").path("COOLING_PAD").asBoolean());
        assertEquals(60, scene.path("resources").path("coolingWaterL").asDouble(), 1e-9);
        assertTrue(scene.path("timeline").toString().contains("SAFETY_INTERLOCK"));
    }

    @Test
    void runtimePadStopsImmediatelyWhenExhaustFaults() {
        // With Java Random's stored 48-bit sequence, seed 2 first chooses exhaust.
        ScenarioSession s = session(2, 22); ObjectNode p = mapper.createObjectNode();
        p.putArray("actions").addObject().put("device", "EXHAUST_FAN").put("duty", .5).put("durationSteps", 4);
        p.withArray("actions").addObject().put("device", "COOLING_PAD").put("duty", .5).put("durationSteps", 4);
        apply(s, p); s.trigger("FAULT");
        assertFalse(s.snapshot().path("deviceHealth").path("EXHAUST_FAN").asBoolean());
        assertFalse(s.snapshot().path("devices").path("COOLING_PAD").asBoolean());
        assertEquals(0, s.advance(frame(1, 22)).path("resources").path("coolingWaterL").asDouble(), 0);
    }

    @Test
    void runtimeClosesAlreadyOpenWindowsWhenStrongWindArrives() {
        ScenarioSession s = session(2, 22); apply(s, "ROOF_VENT", .6, 4);
        assertTrue(s.snapshot().path("devices").path("ROOF_VENT").asBoolean());
        s.trigger("STRONG_WIND");
        assertFalse(s.snapshot().path("devices").path("ROOF_VENT").asBoolean());
        ObjectNode scene = s.advance(frame(1, 22));
        assertEquals(0, scene.path("resources").path("deviceDutyMinutes").path("ROOF_VENT").asDouble(), 1e-9);
    }

    @Test
    void runtimeHighTemperatureStopsHeaterAfterAccountingItsExecutedInterval() {
        ScenarioSession s = session(4, 28.5); apply(s, "HEATING", 1, 4);
        ObjectNode scene = s.advance(frame(1, 28.5));
        assertTrue(scene.path("environment").path("temperatureC").asDouble() >= 29);
        assertFalse(scene.path("devices").path("HEATING").asBoolean());
        assertEquals(20, scene.path("resources").path("electricityKwh").asDouble(), 1e-9);
        scene = s.advance(frame(2, 28.5));
        assertEquals(20, scene.path("resources").path("electricityKwh").asDouble(), 1e-9);
    }

    @Test
    void rejectsFractionalDurationsAndIncompatibleCo2Ventilation() {
        ScenarioSession s = session(5, 22); ObjectNode p = mapper.createObjectNode();
        p.putArray("actions").addObject().put("device", "IRRIGATION").put("duty", .4).put("durationSteps", 2.5);
        assertEquals("BLOCKED", apply(s, p).path("decision").path("status").asText());
        p = mapper.createObjectNode(); p.putArray("actions").addObject().put("device", "CO2_SUPPLY").put("duty", .5).put("durationSteps", 2);
        p.withArray("actions").addObject().put("device", "EXHAUST_FAN").put("duty", .5).put("durationSteps", 2);
        assertEquals("BLOCKED", apply(s, p).path("decision").path("status").asText());
        assertEquals(0, s.snapshot().path("resources").path("electricityKwh").asDouble(), 0);
    }

    @Test
    void checkpointRestoresDeterministicEventAndWaterTrajectory() {
        ScenarioSession original = session(77, 22), restored = session(77, 22);
        original.configure(true, false); apply(original, "IRRIGATION", .5, 8);
        for (int i = 1; i <= 12; i++) original.advance(frame(i, 22));
        restored.restoreCheckpoint(original.checkpoint());
        assertEquals(original.snapshot(), restored.snapshot());
        for (int i = 13; i <= 70; i++) assertEquals(original.advance(frame(i, 22)), restored.advance(frame(i, 22)));
    }

    @Test
    void pendingDecisionRestoresInterruptedWithoutApplyingItsActions() {
        ScenarioSession original = session(8, 22), restored = session(8, 22);
        original.beginDecision("加热", true); restored.restoreCheckpoint(original.checkpoint());
        assertFalse(restored.snapshot().path("pending").asBoolean());
        assertEquals("INTERRUPTED", restored.snapshot().path("decision").path("status").asText());
        assertFalse(restored.snapshot().path("devices").path("HEATING").asBoolean());
    }
}
