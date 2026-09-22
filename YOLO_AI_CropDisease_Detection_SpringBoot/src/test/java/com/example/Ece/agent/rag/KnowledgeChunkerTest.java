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

    /**
     * 剥掉上下文头，只留字段正文。头部的格式是「作物：…；病害：…；字段：…。」，
     * 因此第一个"。"就是头部的结束符。
     */
    private static String body(KnowledgeChunk chunk) {
        String content = chunk.getContent();
        int index = content.indexOf('。');
        return index >= 0 ? content.substring(index + 1) : content;
    }

    /** 每块都必须自带上下文头：没有它，用病名提问会整体失配（实测 Top-1 仅 52.5%）。 */
    @Test
    void everyChunkCarriesContextHeader() {
        List<KnowledgeChunk> chunks = chunker.chunk(9L, "番茄", "早疫病", fields("叶片出现褐色轮纹斑。", "注意通风降湿。"));
        assertEquals(2, chunks.size());
        for (KnowledgeChunk chunk : chunks) {
            assertTrue(chunk.getContent().startsWith("作物：番茄；病害：早疫病；字段："),
                    "块内容必须以作物/病害/字段开头：" + chunk.getContent());
        }
        assertTrue(chunks.get(0).getContent().contains("字段：症状"));
        assertTrue(chunks.get(1).getContent().contains("字段：防治"));
    }

    @Test
    void shortTextBecomesSingleChunk() {
        List<KnowledgeChunk> chunks = chunker.chunk(1L, "番茄", "早疫病", fields("叶片出现褐色轮纹斑。", null));
        assertEquals(1, chunks.size());
        assertEquals("叶片出现褐色轮纹斑。", body(chunks.get(0)));
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
        assertEquals(500, body(chunks.get(0)).length(), "每块正文仍是 500 字，头部不计入切分");
        assertEquals(500, body(chunks.get(1)).length());
        assertEquals(360, body(chunks.get(2)).length());
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
