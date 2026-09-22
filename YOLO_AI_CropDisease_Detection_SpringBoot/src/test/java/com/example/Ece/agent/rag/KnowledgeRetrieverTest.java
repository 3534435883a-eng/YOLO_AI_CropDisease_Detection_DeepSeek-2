package com.example.Ece.agent.rag;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeRetrieverTest {

    private KnowledgeChunk chunk(long id, String content) {
        return new KnowledgeChunk("disease", id, "番茄", "病" + id,
                KnowledgeChunk.FieldType.SYMPTOM, 0, 0, content, "h" + id);
    }

    private List<KnowledgeChunk> corpus() {
        return Arrays.asList(
                chunk(1L, "番茄叶片出现褐色轮纹斑，湿度大时扩展迅速，属早疫病典型症状"),
                chunk(2L, "番茄果实表面出现白色霉层，属灰霉病典型症状"),
                chunk(3L, "番茄茎部腐烂有异味，属细菌性软腐"));
    }

    @Test
    void degradesToBm25WhenEmbeddingUnavailable() {
        KnowledgeRetriever retriever = new KnowledgeRetriever(new EmbeddingClient() {
            public double[] embed(String text) throws EmbeddingUnavailableException {
                throw new EmbeddingUnavailableException("service down");
            }
        });
        retriever.rebuild(corpus());
        RetrievalResult result = retriever.retrieve("褐色轮纹斑", "番茄", 3);
        assertTrue(result.isDegraded());
        assertEquals("EMBEDDING_UNAVAILABLE", result.getDegradedReason());
        assertFalse(result.getItems().isEmpty());
        assertEquals(1L, result.getItems().get(0).getChunk().getSourceId());
    }

    @Test
    void usesVectorWhenAvailableAndMarksNotDegraded() {
        KnowledgeRetriever retriever = new KnowledgeRetriever(new EmbeddingClient() {
            public double[] embed(String text) {
                return new double[]{1.0, 0.0};
            }
        });
        retriever.rebuild(corpus());
        RetrievalResult result = retriever.retrieve("白霉", "番茄", 3);
        assertFalse(result.isDegraded());
        assertTrue(result.getTopScore() > 0.0);
    }

    @Test
    void flagsLowScoreResults() {
        KnowledgeRetriever retriever = new KnowledgeRetriever(new EmbeddingClient() {
            public double[] embed(String text) {
                return new double[]{1.0, 0.0};
            }
        });
        retriever.rebuild(corpus());
        assertTrue(retriever.isLowScore(retriever.retrieve("量子计算机", "番茄", 3)));
        assertFalse(retriever.isLowScore(retriever.retrieve("褐色轮纹斑", "番茄", 3)));
    }
}
