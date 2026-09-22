package com.example.Ece.agent.rag;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeChunkerTest {

    private final KnowledgeChunker chunker = new KnowledgeChunker();

    private Map<KnowledgeChunk.FieldType, String> fields(String symptom, String control) {
        Map<KnowledgeChunk.FieldType, String> map = new LinkedHashMap<KnowledgeChunk.FieldType, String>();
        if (symptom != null) {
            map.put(KnowledgeChunk.FieldType.SYMPTOM, symptom);
        }
        if (control != null) {
            map.put(KnowledgeChunk.FieldType.CONTROL, control);
        }
        return map;
    }

    @Test
    void shortTextBecomesSingleChunk() {
        List<KnowledgeChunk> chunks = chunker.chunk(1L, "番茄", "早疫病", fields("叶片出现褐色轮纹斑。", null));
        assertEquals(1, chunks.size());
        assertEquals("叶片出现褐色轮纹斑。", chunks.get(0).getContent());
        assertEquals(0, chunks.get(0).getChunkNo());
        assertEquals(KnowledgeChunk.FieldType.SYMPTOM, chunks.get(0).getFieldType());
    }

    @Test
    void longTextSplitsWithOverlap() {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < 1200; i++) {
            builder.append('病');
        }
        List<KnowledgeChunk> chunks = chunker.chunk(2L, "番茄", "晚疫病", fields(builder.toString(), null));
        assertEquals(3, chunks.size());
        assertEquals(500, chunks.get(0).getContent().length());
        assertEquals(500, chunks.get(1).getContent().length());
        assertEquals(360, chunks.get(2).getContent().length());
        assertEquals(420, chunks.get(1).getStartOffset());
        assertEquals(840, chunks.get(2).getStartOffset());
    }

    @Test
    void blankFieldsAreSkipped() {
        List<KnowledgeChunk> chunks = chunker.chunk(3L, "番茄", "灰霉病", fields("   ", ""));
        assertTrue(chunks.isEmpty());
    }

    @Test
    void hashIsStableAndDistinctPerChunk() {
        String text = "同一段文本";
        List<KnowledgeChunk> first = chunker.chunk(4L, "番茄", "叶霉病", fields(text, null));
        List<KnowledgeChunk> second = chunker.chunk(4L, "番茄", "叶霉病", fields(text, null));
        assertEquals(first.get(0).getContentHash(), second.get(0).getContentHash());
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 600; i++) {
            longText.append('x');
        }
        List<KnowledgeChunk> many = chunker.chunk(4L, "番茄", "叶霉病", fields(longText.toString(), null));
        assertNotEquals(many.get(0).getContentHash(), many.get(1).getContentHash());
    }

    @Test
    void metadataIsCarriedThrough() {
        List<KnowledgeChunk> chunks = chunker.chunk(77L, "番茄", "早疫病", fields("症状文本", "防治文本"));
        assertEquals(2, chunks.size());
        assertEquals(77L, chunks.get(0).getSourceId());
        assertEquals("番茄", chunks.get(0).getCropType());
        assertEquals("早疫病", chunks.get(0).getDiseaseName());
        assertEquals("disease", chunks.get(0).getSourceTable());
    }
}
