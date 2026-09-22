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

    /**
     * 复用已入库向量：库里存了向量就不该每次启动把几百个块重新向量化一遍。
     * 这里断言 embed 调用次数为 0，且向量召回仍然可用（未降级）。
     */
    @Test
    void rebuildReusesStoredEmbeddingsWithoutCallingEmbeddingService() {
        final int[] embedCalls = {0};
        KnowledgeRetriever retriever = new KnowledgeRetriever(new EmbeddingClient() {
            public double[] embed(String text) {
                embedCalls[0]++;
                return new double[]{1.0, 0.0};
            }
        });
        List<double[]> stored = Arrays.asList(
                new double[]{1.0, 0.0}, new double[]{1.0, 0.0}, new double[]{1.0, 0.0});
        retriever.rebuild(corpus(), stored);

        assertEquals(0, embedCalls[0], "存量向量可用时不得再次调用向量服务");
        RetrievalResult result = retriever.retrieve("白霉", "番茄", 3);
        assertFalse(result.isDegraded(), "复用存量向量后向量召回应可用");
        assertFalse(result.getItems().isEmpty());
    }

    /** 存量向量不完整（含空向量）时必须退回现场向量化，而不是悄悄丢掉向量召回。 */
    @Test
    void rebuildFallsBackToEmbeddingWhenStoredVectorsIncomplete() {
        final int[] embedCalls = {0};
        KnowledgeRetriever retriever = new KnowledgeRetriever(new EmbeddingClient() {
            public double[] embed(String text) {
                embedCalls[0]++;
                return new double[]{1.0, 0.0};
            }
        });
        List<double[]> incomplete = Arrays.asList(new double[]{1.0, 0.0}, null, new double[]{1.0, 0.0});
        retriever.rebuild(corpus(), incomplete);

        assertEquals(corpus().size(), embedCalls[0], "向量不完整时应逐个重新向量化");
        assertFalse(retriever.retrieve("白霉", "番茄", 3).isDegraded());
    }

    /**
     * 只命中作物名的提问必须判为低分。实测语料上"如何给番茄施肥"就是这种情况：
     * 它命中"番茄"等泛词，语料级覆盖率一度达 0.31，靠单一比值判据会被放行（实测漏判）。
     */
    @Test
    void refusesQueryWhoseOnlyMatchIsTheCropName() {
        KnowledgeRetriever retriever = new KnowledgeRetriever(new EmbeddingClient() {
            public double[] embed(String text) {
                return new double[]{1.0, 0.0};
            }
        });
        retriever.rebuild(corpus());
        RetrievalResult result = retriever.retrieve("番茄怎么卖", "番茄", 3);
        assertFalse(result.getItems().isEmpty(), "本例的前提是确实召回了东西，否则测不到判据");
        assertTrue(result.getMaxChunkMatchedTerms() <= 1, "只应命中作物名："
                + result.getMaxChunkMatchedTerms());
        assertTrue(retriever.isLowScore(result));
    }

    /**
     * 长问句即使覆盖率被长度稀释，只要 Top 块里有两个以上查询词共现，就必须作答。
     * 这正是旧判据（单一覆盖率阈值 0.30）会误杀的情形，锁住以防回退。
     */
    @Test
    void acceptsLongQueryWhenSymptomTermsCoOccur() {
        KnowledgeRetriever retriever = new KnowledgeRetriever(new EmbeddingClient() {
            public double[] embed(String text) {
                return new double[]{1.0, 0.0};
            }
        });
        retriever.rebuild(corpus());
        RetrievalResult result = retriever.retrieve(
                "番茄叶片上出现的褐色轮纹斑一直在扩展，湿度也大，这种情况该怎么办", "番茄", 3);
        assertTrue(result.getMaxChunkMatchedTerms() >= KnowledgeRetriever.MIN_CHUNK_TERMS,
                "共现词数应达标：" + result.getMaxChunkMatchedTerms());
        assertFalse(retriever.isLowScore(result));
    }
}
