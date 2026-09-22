package com.example.Ece.agent.rag;

import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 知识索引装配：把库里已有的知识块（连同**已入库向量**）载入内存索引。
 *
 * <p>与 {@link KnowledgeBootstrap} 的分工：本类**始终存在**，只负责"把索引建起来"，
 * 因此状态接口随时可以调用它刷新；灌数据的启动逻辑放在条件化的 Bootstrap 里，
 * 测试环境中可以单独关闭，避免单元测试往开发库里写数据。</p>
 *
 * <p><b>读状态不等于重建索引</b>（实测踩过）：原实现让 {@code GET /ai/knowledge/status} 每次都
 * 全量重载块表并重建 BM25/向量索引（实测中位 84 ms、且每次都打数据库）。状态接口是会被前端
 * 轮询的，这种"读一次重建一次"的设计既浪费又会在并发下反复抢 CPU。现在改为：
 * {@link #currentSummary()} 只读上一次的快照，重建只走 {@link #reload()}（启动、reingest、
 * 显式 {@code POST /ai/knowledge/reload}）。</p>
 */
@Service
public class KnowledgeIndexService {

    private final KnowledgeRetriever retriever;
    private final KnowledgeChunkRepository chunkRepository;
    private final KnowledgeSourceRepository sourceRepository;

    /** 最近一次重建后的快照；为 null 表示尚未建过索引。 */
    private volatile KnowledgeLoadSummary lastSummary;
    private volatile long lastReloadAtMillis;

    public KnowledgeIndexService(KnowledgeRetriever retriever, KnowledgeChunkRepository chunkRepository,
                                 KnowledgeSourceRepository sourceRepository) {
        this.retriever = retriever;
        this.chunkRepository = chunkRepository;
        this.sourceRepository = sourceRepository;
    }

    /**
     * 载入全部知识块与已有向量并重建内存索引，返回可展示的摘要。
     *
     * <p>{@code synchronized}：并发调用（启动 + 手动 reload + reingest）不该同时重建同一份索引。</p>
     */
    public synchronized KnowledgeLoadSummary reload() {
        long startedAt = System.currentTimeMillis();
        List<KnowledgeChunk> chunks = chunkRepository.loadAll();
        List<double[]> embeddings = chunkRepository.loadEmbeddings();
        retriever.rebuild(chunks, embeddings);
        int vectorRows = 0;
        for (double[] vector : embeddings) {
            if (vector != null && vector.length > 0) {
                vectorRows++;
            }
        }
        long finishedAt = System.currentTimeMillis();
        KnowledgeLoadSummary summary = new KnowledgeLoadSummary(chunks.size(), vectorRows,
                sourceRepository.findAll().size(), finishedAt - startedAt, finishedAt,
                retriever.isVectorAvailable());
        lastSummary = summary;
        lastReloadAtMillis = finishedAt;
        return summary;
    }

    /**
     * 当前索引快照，**不重建**。
     *
     * <p>只有从未建过索引时（例如 Bootstrap 被关闭的测试环境）才顺带建一次，
     * 否则返回上一次 {@link #reload()} 的结果。</p>
     */
    public KnowledgeLoadSummary currentSummary() {
        KnowledgeLoadSummary cached = lastSummary;
        return cached != null ? cached : reload();
    }

    /** 最近一次重建时间（epoch ms）；0 表示从未重建。 */
    public long getLastReloadAtMillis() {
        return lastReloadAtMillis;
    }

    /** 知识库装载摘要，供启动日志与状态接口共用。 */
    public static final class KnowledgeLoadSummary {

        private final int chunkCount;
        private final int vectorCount;
        private final int sourceCount;
        private final long reloadMillis;
        private final long loadedAtMillis;
        private final boolean vectorSearchAvailable;

        KnowledgeLoadSummary(int chunkCount, int vectorCount, int sourceCount, long reloadMillis,
                             long loadedAtMillis, boolean vectorSearchAvailable) {
            this.chunkCount = chunkCount;
            this.vectorCount = vectorCount;
            this.sourceCount = sourceCount;
            this.reloadMillis = reloadMillis;
            this.loadedAtMillis = loadedAtMillis;
            this.vectorSearchAvailable = vectorSearchAvailable;
        }

        public int getChunkCount() { return chunkCount; }

        public int getVectorCount() { return vectorCount; }

        public int getSourceCount() { return sourceCount; }

        /** 本次重建耗时（ms）。 */
        public long getReloadMillis() { return reloadMillis; }

        /** 快照产生时间（epoch ms）；状态接口据此判断"这个数字有多旧"。 */
        public long getLoadedAtMillis() { return loadedAtMillis; }

        /** 索引里向量是否真的可用（块表有向量且可复用时为 true）。 */
        public boolean isVectorSearchAvailable() { return vectorSearchAvailable; }
    }
}