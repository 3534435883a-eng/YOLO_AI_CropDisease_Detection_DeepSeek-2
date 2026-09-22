package com.example.Ece.agent.rag;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Bm25IndexTest {

    private KnowledgeChunk chunk(long id, String content) {
        return new KnowledgeChunk("disease", id, "番茄", "病" + id,
                KnowledgeChunk.FieldType.SYMPTOM, 0, 0, content, "h" + id);
    }

    private Bm25Index indexOf(KnowledgeChunk... chunks) {
        Bm25Index index = new Bm25Index();
        index.rebuild(Arrays.asList(chunks));
        return index;
    }

    @Test
    void ranksChunkContainingRareTermFirst() {
        Bm25Index index = indexOf(
                chunk(1L, "番茄叶片出现褐色轮纹斑，湿度大时病斑扩展迅速"),
                chunk(2L, "番茄果实表面出现白色霉层"),
                chunk(3L, "番茄茎部腐烂并伴有异味"));
        List<ScoredChunk> hits = index.search("褐色轮纹斑", 3);
        assertFalse(hits.isEmpty());
        assertEquals(1L, hits.get(0).getChunk().getSourceId());
        assertEquals(1, hits.get(0).getRank());
        assertTrue(hits.get(0).getScore() > 0.0);
    }

    @Test
    void rareTermOutweighsCommonTerm() {
        Bm25Index index = indexOf(
                chunk(1L, "番茄 番茄 番茄 番茄"),
                chunk(2L, "番茄 晚疫病"));
        List<ScoredChunk> hits = index.search("番茄 晚疫病", 2);
        assertEquals(2L, hits.get(0).getChunk().getSourceId());
    }

    @Test
    void respectsTopKAndEmptyQuery() {
        Bm25Index index = indexOf(chunk(1L, "番茄早疫病"), chunk(2L, "番茄晚疫病"));
        assertEquals(1, index.search("番茄", 1).size());
        assertTrue(index.search("", 5).isEmpty());
        assertTrue(index.search(null, 5).isEmpty());
    }
}
