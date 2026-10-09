package com.example.Ece.agent.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AgentContextSummaryTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void taskFactsExcludeDuplicatedSceneAndPreserveOriginalArchive() {
        ObjectNode frozen = mapper.createObjectNode();
        frozen.putObject("task").put("id", "case-1").putArray("evidence").addObject().put("label", "早疫病候选").put("diagnosisConfirmed", false);
        frozen.putObject("current").put("largeScene", repeat(24000));
        frozen.put("sourceBoundary", "M3历史回放与虚拟处置有不同来源");
        ObjectNode summary = AgentContextSummary.taskFacts(frozen);
        assertFalse(summary.has("current"));
        assertEquals("case-1", summary.path("task").path("id").asText());
        assertFalse(summary.path("task").path("evidence").get(0).path("diagnosisConfirmed").asBoolean());
        assertEquals(24000, frozen.path("current").path("largeScene").asText().length());
        assertTrue(summary.toString().length() < 10000);
    }

    @Test void frameSummaryRetainsNumbersOriginsAndActiveEffectsWithinMessageBudget() {
        ObjectNode frame = mapper.createObjectNode(); frame.put("at", "2025-04-19T18:30");
        frame.putObject("environment").put("temperatureC", 26.4).put("origin", "M3_OBSERVATION");
        ObjectNode scenario = frame.putObject("scenario");
        scenario.putArray("parameters").addObject().put("notes", repeat(24000));
        scenario.putObject("agronomy").put("soilMoistureVwcPct", 29.8).put("measured", false).putArray("notes").add(repeat(18000));
        scenario.putArray("effects").addObject().put("device", "HEATING").put("status", "IDLE");
        for (int i = 0; i < 10; i++) scenario.withArray("effects").addObject().put("status", "ACTIVE").put("device", "device-"+i).put("observed", repeat(3000));
        ObjectNode summary = AgentContextSummary.frame(frame);
        assertTrue(summary.toString().length() <= 10000);
        assertEquals(26.4, summary.path("environment").path("temperatureC").asDouble(), 0);
        assertEquals(29.8, summary.path("scenario").path("agronomy").path("soilMoistureVwcPct").asDouble(), 0);
        assertFalse(summary.path("scenario").has("parameters"));
        assertTrue(summary.path("contextTruncated").asBoolean());
        assertTrue(frame.path("scenario").has("parameters"));
        for (com.fasterxml.jackson.databind.JsonNode effect : summary.path("scenario").path("effects")) assertEquals("ACTIVE", effect.path("status").asText());
    }

    @Test void largeTaskKeepsLatestEvidenceAndNeverMutatesFrozenRecords() {
        ObjectNode frozen = mapper.createObjectNode(); ObjectNode task = frozen.putObject("task"); task.put("id", "case-2");
        for (int i = 0; i < 12; i++) task.withArray("evidence").addObject().put("id", "evidence-"+i).put("details", repeat(4000));
        ObjectNode summary = AgentContextSummary.taskFacts(frozen);
        assertEquals(12, frozen.path("task").path("evidence").size());
        com.fasterxml.jackson.databind.JsonNode rows = summary.path("task").path("evidence");
        assertEquals("evidence-11", rows.get(rows.size()-1).path("id").asText());
        assertTrue(summary.toString().length() <= 10000);
    }
    @Test void realPausedApiSceneRetainsReceiptDifferencesWithoutDuplicatingPhysiology() throws Exception {
        ObjectNode run = (ObjectNode) mapper.readTree(getClass().getResourceAsStream("/fixtures/m3-paused-feedback.json"));
        ObjectNode frame = (ObjectNode) run.path("current");
        ObjectNode original = frame.deepCopy();
        ObjectNode summary = AgentContextSummary.frame(frame);
        assertTrue(summary.toString().length() <= 10000);
        assertEquals(original, frame);
        assertEquals(frame.path("at"), summary.path("at"));
        assertEquals(frame.path("environment"), summary.path("environment"));
        assertEquals(frame.path("scenario").path("agronomy").path("soilMoistureVwcPct"), summary.path("scenario").path("agronomy").path("soilMoistureVwcPct"));
        for (String key : new String[]{"at", "temperatureDifferenceC", "humidityDifferencePct", "soilMoistureDifferenceVwcPct", "applicationStatus", "fieldValidated"})
            assertEquals(frame.path("scenario").path("decision").path("feedback").path(key), summary.path("scenario").path("decision").path("feedback").path(key));
        assertTrue(summary.path("scenario").path("decision").path("feedback").path("detailsSummarized").asBoolean());
    }
    private String repeat(int count) { char[] chars = new char[count]; java.util.Arrays.fill(chars, '农'); return new String(chars); }
}
