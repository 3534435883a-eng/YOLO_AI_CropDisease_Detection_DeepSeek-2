package com.example.Ece.agent.rag;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CitationFormatterTest {

    private final CitationFormatter formatter = new CitationFormatter();

    private ScoredChunk hit(long id, String name, KnowledgeChunk.FieldType type, int chunkNo, double score) {
        return new ScoredChunk(new KnowledgeChunk("disease", id, "番茄", name, type, chunkNo, 0,
                "叶片出现褐色轮纹斑", "h" + id), score, 1);
    }

    @Test
    void buildsNumberedCitations() {
        List<Map<String, Object>> citations = formatter.toCitations(Arrays.asList(
                hit(1L, "早疫病", KnowledgeChunk.FieldType.SYMPTOM, 0, 0.0325),
                hit(2L, "晚疫病", KnowledgeChunk.FieldType.CONTROL, 1, 0.0161)));
        assertEquals(2, citations.size());
        assertEquals(1, citations.get(0).get("index"));
        assertEquals("早疫病", citations.get(0).get("diseaseName"));
        assertEquals("SYMPTOM", citations.get(0).get("fieldType"));
        assertEquals(0, citations.get(0).get("chunkNo"));
        assertTrue(String.valueOf(citations.get(0).get("label")).contains("早疫病"));
    }

    @Test
    void promptBlockCarriesIndexAndSource() {
        String block = formatter.toPromptBlock(Arrays.asList(hit(1L, "早疫病", KnowledgeChunk.FieldType.SYMPTOM, 0, 0.03)));
        assertTrue(block.contains("[1]"));
        assertTrue(block.contains("早疫病"));
        assertTrue(block.contains("叶片出现褐色轮纹斑"));
    }

    @Test
    void promptAndCitationCarryVerifiableProvenance() {
        KnowledgeChunk chunk = new KnowledgeChunk("curated_tomato", 2L, "番茄", "番茄黄化曲叶病",
                KnowledgeChunk.FieldType.SYMPTOM, 0, 0, "叶片变小并向上卷曲", "curated-2",
                "ucipm-tomato-yellow-leaf-curl", "UC IPM: Tomato Yellow Leaf Curl", "B",
                "https://ipm.ucanr.edu/agriculture/tomato/tomato-yellow-leaf-curl/", "2026-09-24-reviewed");
        ScoredChunk hit = new ScoredChunk(chunk, 0.03, 1);

        Map<String, Object> citation = formatter.toCitations(Arrays.asList(hit)).get(0);
        assertEquals("UC IPM: Tomato Yellow Leaf Curl", citation.get("sourceName"));
        assertEquals(chunk.getSourceUrl(), citation.get("sourceUrl"));
        String block = formatter.toPromptBlock(Arrays.asList(hit));
        assertTrue(block.contains("UC IPM: Tomato Yellow Leaf Curl"));
        assertTrue(block.contains("2026-09-24-reviewed"));
        assertTrue(block.contains(chunk.getSourceUrl()));
    }
}
