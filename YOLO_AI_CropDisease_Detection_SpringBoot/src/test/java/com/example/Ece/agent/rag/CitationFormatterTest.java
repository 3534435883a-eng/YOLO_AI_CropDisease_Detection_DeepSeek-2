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
}
