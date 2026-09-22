package com.example.Ece.agent.rag;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RrfFusionTest {

    private final RrfFusion fusion = new RrfFusion();

    private ScoredChunk hit(long id, double score, int rank) {
        return new ScoredChunk(new KnowledgeChunk("disease", id, "番茄", "d" + id,
                KnowledgeChunk.FieldType.SYMPTOM, 0, 0, "c" + id, "h" + id), score, rank);
    }

    @Test
    void fusesTwoRankedListsByReciprocalRank() {
        List<ScoredChunk> bm25 = Arrays.asList(hit(1L, 9.0, 1), hit(2L, 8.0, 2), hit(3L, 7.0, 3));
        List<ScoredChunk> vector = Arrays.asList(hit(3L, 0.9, 1), hit(1L, 0.8, 2), hit(4L, 0.7, 3));
        List<ScoredChunk> fused = fusion.fuse(Arrays.asList(bm25, vector), 60, 4);
        assertEquals(4, fused.size());
        assertEquals(1L, fused.get(0).getChunk().getSourceId());
        assertEquals(3L, fused.get(1).getChunk().getSourceId());
        assertEquals(2L, fused.get(2).getChunk().getSourceId());
        assertEquals(4L, fused.get(3).getChunk().getSourceId());
        assertEquals(1, fused.get(0).getRank());
    }

    @Test
    void truncatesToTopNAndSurvivesEmptyList() {
        List<ScoredChunk> only = Arrays.asList(hit(1L, 1.0, 1), hit(2L, 0.5, 2));
        assertEquals(1, fusion.fuse(Arrays.asList(only, new ArrayList<ScoredChunk>()), 60, 1).size());
        assertTrue(fusion.fuse(new ArrayList<List<ScoredChunk>>(), 60, 5).isEmpty());
    }
}
