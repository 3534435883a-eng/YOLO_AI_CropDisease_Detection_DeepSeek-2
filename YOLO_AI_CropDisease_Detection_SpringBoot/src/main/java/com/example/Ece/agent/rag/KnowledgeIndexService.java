package com.example.Ece.agent.rag;

import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 知识索引装配：把库里已有的知识块（连同**已入库向量**）载入内存索引。
 *
 * <p>与 {@link KnowledgeBootstrap} 的分工：本类**始终存在**，只负责"把索引建起来"，
 * 因此状态接口随时可以调用它刷新；灌数据的启动逻辑放在条件化的 Bootstrap 里，
 * 测试环境中可以单独关闭，避免单元测试往开发库里写数据。</p>
 */
@Service
public class KnowledgeIndexService {

    private final KnowledgeRetriever retriever;
    private final KnowledgeChunkRepository chunkRepository;
    private final KnowledgeSourceRepository sourceRepository;

    public KnowledgeIndexService(KnowledgeRetriever retriever, KnowledgeChunkRepository chunkRepository,
                                 KnowledgeSourceRepository sourceRepository) {
        this.retriever = retriever;
        this.chunkRepository = chunkRepository;
        this.sourceRepository = sourceRepository;
    }

    /** 载入全部知识块与已有向量并重建内存索引，返回可展示的摘要。 */
    public KnowledgeLoadSummary reload() {
        List<KnowledgeChunk> chunks = chunkRepository.loadAll();
        List<double[]> embeddings = chunkRepository.loadEmbeddings();
        retriever.rebuild(chunks, embeddings);
        int vectorRows = 0;
        for (double[] vector : embeddings) {
            if (vector != null && vector.length > 0) {
                vectorRows++;
            }
        }
        return new KnowledgeLoadSummary(chunks.size(), vectorRows, sourceRepository.findAll().size());
    }

    /** 知识库装载摘要，供启动日志与状态接口共用。 */
    public static final class KnowledgeLoadSummary {

        private final int chunkCount;
        private final int vectorCount;
        private final int sourceCount;

        KnowledgeLoadSummary(int chunkCount, int vectorCount, int sourceCount) {
            this.chunkCount = chunkCount;
            this.vectorCount = vectorCount;
            this.sourceCount = sourceCount;
        }

        public int getChunkCount() { return chunkCount; }

        public int getVectorCount() { return vectorCount; }

        public int getSourceCount() { return sourceCount; }
    }
}