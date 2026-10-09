package com.example.Ece.agent.m3;

import com.example.Ece.agent.crop.TomatoCropGrowthModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class M3LiveServiceTest {
    @TempDir Path directory;
    final ObjectMapper mapper = new ObjectMapper();
    M3LiveService service;

    @BeforeEach void setUp() throws IOException {
        M3ObservationService observations = mock(M3ObservationService.class);
        M3MultiYearCalibrationService calibration = mock(M3MultiYearCalibrationService.class);
        when(observations.root()).thenReturn(directory);
        ObjectNode data = mapper.createObjectNode(); data.put("observationsSha256", "frozen-test-input");
        ObjectNode season = data.putArray("seasons").addObject(); season.put("year", 2025); season.put("startDate", "2025-04-19");
        season.putObject("cohort").putArray("plantIds").add("CK-1");
        LocalDateTime start = LocalDateTime.of(2025, 4, 19, 12, 0);
        for (int i = 0; i < 96; i++) {
            ObjectNode env = season.withArray("environment").addObject();
            env.put("at", start.plusMinutes(i*30L).toString()); env.put("temperatureC", 24); env.put("airHumidityPct", 70);
            env.put("co2Ppm", 500); env.put("lightRaw", 10); env.put("estimated", false); env.put("origin", "TEST_OBSERVATION");
        }
        for (int day = 0; day < 2; day++) {
            ObjectNode row = season.withArray("growth").addObject();
            row.put("observedDate", start.toLocalDate().plusDays(day).toString()); row.put("plantId", "CK-1");
            row.put("selected", true); row.putArray("qualityFlags"); row.put("metric", "plantHeightCm"); row.put("value", day==0?25:45);
            row.putObject("source").put("origin", "TEST_MEASUREMENT");
        }
        when(observations.read()).thenReturn(data);
        ObjectNode frozen = mapper.createObjectNode(); frozen.put("version", "test-frozen-2023-2024");
        frozen.putObject("parameters").put("phyllochronGdd", 35).put("maxInternodeLengthCm", 9).put("linearHeightCmPerGdd", .1);
        when(calibration.latest()).thenReturn(frozen);
        service = new M3LiveService(observations, calibration, new TomatoCropGrowthModel(), mapper);
    }
    @AfterEach void close() { if (service!=null) service.shutdownClock(); }

    @Test void startDoesNotRevealFutureMeasurementsAndIncrementalReadsContainOnlyConsumedFrames() throws IOException {
        ObjectNode initial = service.start(2025); String id = initial.path("runId").asText();
        service.scenarioCommand(id,"configure",mapper.createObjectNode().put("autoEvents",false).put("autoActuation",false));
        assertEquals(0, initial.path("cursor").asInt()); assertEquals(1, initial.path("frames").size());
        assertEquals(25, initial.path("current").path("observedHeightCm").asDouble(), 0);
        ObjectNode next = service.step(id, 0, 1);
        assertTrue(next.path("current").path("observedHeightCm").isNull());
        assertEquals(0, next.path("updateCount").asInt());
        assertEquals(1, service.current(id,false,0).path("frames").size());
        assertEquals(0, service.current(id,true).path("frames").size());
        assertThrows(IOException.class, () -> service.current(id,false,2));
    }

    @Test void observedHeightOnlyUpdatesAtArrivalAndPriorScorePrecedesAssimilation() throws IOException {
        String id = service.start(2025).path("runId").asText();
        service.scenarioCommand(id,"configure",mapper.createObjectNode().put("autoEvents",false).put("autoActuation",false));
        ObjectNode next = service.step(id,0,48);
        assertEquals("2025-04-20T12:00", next.path("current").path("at").asText());
        assertEquals(1, next.path("updateCount").asInt());
        assertEquals(45, next.path("current").path("observedHeightCm").asDouble(),0);
        assertEquals(1, next.path("scores").path("prior").path("n").asInt());
        assertTrue(next.path("scores").path("posterior").path("mae").asDouble() < next.path("scores").path("prior").path("mae").asDouble());
        assertTrue(next.path("current").path("predictedHeightCm").asDouble()!=45);
    }

    @Test void manualStepsRejectStaleCursorAndPlayingRunWithoutAdvancing() throws IOException {
        String id = service.start(2025).path("runId").asText();
        assertThrows(IOException.class, () -> service.step(id,1,1));
        assertThrows(IOException.class, () -> service.step(id,0,2));
        assertEquals(0, service.current(id,true).path("cursor").asInt());
        service.playback(id,true,1);
        assertThrows(IOException.class, () -> service.step(id,0,1));
        service.playback(id,false,1);
    }

    @Test void releaseRestoresExactConsumedAgronomyFilterAndRandomStatePaused() throws IOException {
        String id = service.start(2025).path("runId").asText();
        service.scenarioCommand(id,"configure",mapper.createObjectNode().put("autoEvents",false).put("autoActuation",false));
        ObjectNode before = service.step(id,0,12);
        assertFalse(before.path("playback").has("persistenceError"));
        service.delete(id);
        ObjectNode restored = service.current(id,false);
        assertTrue(restored.path("playback").path("restored").asBoolean());
        assertFalse(restored.path("playback").path("playing").asBoolean());
        assertEquals(before.path("current"), restored.path("current"));
        assertEquals(before.path("cursor"), restored.path("cursor"));
        assertEquals(13, restored.path("frames").size());
        assertEquals(13, service.step(id,12,1).path("cursor").asInt());
    }

    @Test void rejectsUnsupportedSeasonAndTraversalIdentifiers() {
        assertThrows(IOException.class, () -> service.start(2022));
        assertThrows(IOException.class, () -> service.current("../../private",true));
    }
}
