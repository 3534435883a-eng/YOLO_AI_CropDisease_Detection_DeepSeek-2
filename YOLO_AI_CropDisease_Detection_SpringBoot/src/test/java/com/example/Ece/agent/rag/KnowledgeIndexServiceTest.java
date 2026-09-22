package com.example.Ece.agent.rag;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 知识索引服务：只管"建索引"与"报快照"，**读状态不等于重建索引**。
 *
 * <p>被实测逼迫出来的用例：原实现让 {@code GET /ai/knowledge/status} 每次都全量重载块表并重建
 * 索引（中位 84 ms、每次打数据库）。状态接口会被前端轮询，因此这里用计数仓储把"重建次数"钉死。</p>
 */
class KnowledgeIndexServiceTest {

    private final CountingChunkRepository chunks = new CountingChunkRepository();
    private final CountingSourceRepository sources = new CountingSourceRepository();
    private final KnowledgeRetriever retriever = new KnowledgeRetriever(new StubEmbeddingClient());

    private KnowledgeIndexService service() {
        return new KnowledgeIndexService(retriever, chunks, sources);
    }

    @Test
    void reloadReadsTheRepositoriesAndReportsTheSnapshot() {
        KnowledgeIndexService.KnowledgeLoadSummary summary = service().reload();

        assertEquals(1, chunks.loadAllCalls, "reload 必须把块读一次");
        assertEquals(1, chunks.loadEmbeddingCalls, "reload 必须把向量读一次");
        assertEquals(1, summary.getChunkCount());
        assertEquals(1, summary.getVectorCount());
        assertTrue(summary.isVectorSearchAvailable(), "块表带向量时应复用，不必现场向量化");
        assertTrue(summary.getReloadMillis() >= 0L);
        assertTrue(summary.getLoadedAtMillis() > 0L, "快照必须带时间戳，调用方才能判断它有多旧");
    }

    /**
     * 本用例是这次修改的核心：读状态不得触发重建。
     * 若有人把 {@code currentSummary()} 改回"每次都 reload"，这里立刻红。
     */
    @Test
    void currentSummaryDoesNotRebuildTheIndex() {
        KnowledgeIndexService service = service();
        service.reload();
        assertEquals(1, chunks.loadAllCalls);

        service.currentSummary();
        service.currentSummary();

        assertEquals(1, chunks.loadAllCalls, "读状态不得再次读块表");
        assertEquals(1, chunks.loadEmbeddingCalls, "读状态不得再次读向量");
    }

    /** 从未建过索引时（例如测试环境关掉了 Bootstrap），读状态要能自己兜住，而不是返回空计数。 */
    @Test
    void currentSummaryBuildsLazilyWhenNothingWasLoadedYet() {
        KnowledgeIndexService.KnowledgeLoadSummary summary = service().currentSummary();

        assertEquals(1, chunks.loadAllCalls, "首次读状态仍需建一次索引");
        assertEquals(1, summary.getChunkCount());
    }

    /** 重复 reload 会更新快照内容（外部直接改库后需要这个入口）。 */
    @Test
    void reloadRefreshesTheSnapshot() {
        KnowledgeIndexService service = service();
        KnowledgeIndexService.KnowledgeLoadSummary first = service.reload();
        chunks.extraChunk = symptomChunk(2L, "玉米", "玉米锈病", "叶片出现褐色孢子堆");
        KnowledgeIndexService.KnowledgeLoadSummary second = service.reload();

        assertEquals(1, first.getChunkCount());
        assertEquals(2, second.getChunkCount(), "重新 reload 必须反映库里的新块");
        assertSame(second, service.currentSummary(), "currentSummary 返回的是最近一次快照");
        assertEquals(2, chunks.loadAllCalls);
    }

    private static KnowledgeChunk symptomChunk(long sourceId, String crop, String disease, String text) {
        Map<KnowledgeChunk.FieldType, String> fields = new LinkedHashMap<KnowledgeChunk.FieldType, String>();
        fields.put(KnowledgeChunk.FieldType.SYMPTOM, text);
        return new KnowledgeChunker().chunk("disease", sourceId, crop, disease, fields).get(0);
    }

    /** 向量服务假实现：rebuild 走"已入库向量"路径时不会被调用。 */
    private static final class StubEmbeddingClient implements EmbeddingClient {
        public double[] embed(String text) {
            return new double[]{1.0, 0.0};
        }
    }

    private static final class CountingChunkRepository implements KnowledgeChunkRepository {

        private final List<KnowledgeChunk> stored = new ArrayList<KnowledgeChunk>();
        private final List<double[]> vectors = new ArrayList<double[]>();
        private KnowledgeChunk extraChunk;
        private int loadAllCalls;
        private int loadEmbeddingCalls;

        CountingChunkRepository() {
            stored.add(symptomChunk(1L, "番茄", "番茄早疫病", "叶片出现褐色轮纹斑"));
            vectors.add(new double[]{0.5, 0.5});
        }

        public Set<String> existingContentHashes() {
            Set<String> hashes = new LinkedHashSet<String>();
            for (KnowledgeChunk chunk : all()) {
                hashes.add(chunk.getContentHash());
            }
            return hashes;
        }

        public int saveAll(String sourceCode, int authorityLevel, List<KnowledgeChunk> chunkList,
                           List<double[]> embeddings, String embeddingModel) {
            return 0;
        }

        public int deleteBySourceCode(String sourceCode) {
            return 0;
        }

        public List<KnowledgeChunk> loadAll() {
            loadAllCalls++;
            return all();
        }

        public List<double[]> loadEmbeddings() {
            loadEmbeddingCalls++;
            return new ArrayList<double[]>(vectors);
        }

        private List<KnowledgeChunk> all() {
            List<KnowledgeChunk> list = new ArrayList<KnowledgeChunk>(stored);
            if (extraChunk != null) {
                list.add(extraChunk);
            }
            return list;
        }
    }

    private static final class CountingSourceRepository implements KnowledgeSourceRepository {

        public void save(KnowledgeSource source) {
        }

        public KnowledgeSource findByCode(String sourceCode, String version) {
            return null;
        }

        public List<KnowledgeSource> findAll() {
            List<KnowledgeSource> list = new ArrayList<KnowledgeSource>();
            list.add(new KnowledgeSource("SRC-TEST", "测试来源", "E", 5, null, null, "v1"));
            return list;
        }
    }
}
