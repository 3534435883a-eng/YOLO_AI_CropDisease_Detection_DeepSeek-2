package com.example.Ece.agent.rag;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CuratedCropKnowledgeReaderTest {

    @Test
    void everyCuratedRecordHasTraceableSourceAndDistinctOrigin() {
        List<CuratedCropKnowledgeReader.SourceEntry> entries = new CuratedCropKnowledgeReader().readAll();
        assertEquals(12, entries.size());
        Set<String> codes = new HashSet<String>();
        Set<String> origins = new HashSet<String>();
        int tomatoCount = 0;
        int appleCount = 0;
        for (CuratedCropKnowledgeReader.SourceEntry entry : entries) {
            KnowledgeSource source = entry.getSource();
            IngestRecord record = entry.getRecord();
            assertTrue(source.getUrl().startsWith("https://") || source.getUrl().startsWith("http://"));
            assertFalse(source.getSourceName().trim().isEmpty());
            assertFalse(source.getVersion().trim().isEmpty());
            assertFalse(source.getLicenseNote().trim().isEmpty());
            assertEquals("B", source.getSourceType());
            assertEquals(3, record.getFields().size());
            assertTrue(codes.add(source.getSourceCode()));
            assertTrue(origins.add(record.getSourceTable() + ":" + record.getSourceId()));
            if ("curated_tomato".equals(record.getSourceTable())) {
                assertEquals("番茄", record.getCropType());
                tomatoCount++;
            } else {
                assertEquals("curated_apple", record.getSourceTable());
                assertEquals("苹果", record.getCropType());
                appleCount++;
            }
        }
        assertEquals(11, tomatoCount);
        assertEquals(1, appleCount);
    }
}
