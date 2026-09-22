package com.example.Ece.agent.rag;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 四层检索入口：BM25 + 向量 → RRF 融合。
 * 向量服务不可用时降级为纯关键词检索，并在结果中显式标记降级原因。
 */
@Component
public class KnowledgeRetriever {

    public static final int TOP_K_EACH = 20;
    public static final int RRF_K = 60;
    public static final double MIN_SCORE = 0.016;
    public static final String DEGRADED_EMBEDDING = "EMBEDDING_UNAVAILABLE";

    private final EmbeddingClient embeddingClient;
    private final Bm25Index bm25Index = new Bm25Index();
    private final VectorIndex vectorIndex = new VectorIndex();
    private final RrfFusion fusion = new RrfFusion();
    private boolean vectorAvailable = false;

    public KnowledgeRetriever(EmbeddingClient embeddingClient) {
        this.embeddingClient = embeddingClient;
    }

    public void rebuild(List<KnowledgeChunk> chunks) {
        bm25Index.rebuild(chunks);
        List<KnowledgeChunk> indexed = chunks == null ? new ArrayList<KnowledgeChunk>() : chunks;
        List<double[]> embeddings = new ArrayList<double[]>();
        boolean embedded = !indexed.isEmpty();
        if (embedded) {
            for (KnowledgeChunk chunk : indexed) {
                try {
                    embeddings.add(embeddingClient.embed(chunk.getContent()));
                } catch (EmbeddingUnavailableException error) {
                    embedded = false;
                    break;
                }
            }
        }
        vectorAvailable = embedded;
        if (embedded) {
            vectorIndex.rebuild(indexed, embeddings);
        } else {
            vectorIndex.rebuild(new ArrayList<KnowledgeChunk>(), new ArrayList<double[]>());
        }
    }

    public RetrievalResult retrieve(String query, String cropType, int topN) {
        List<ScoredChunk> bm25Hits = filterByCrop(bm25Index.search(query, TOP_K_EACH), cropType);
        List<List<ScoredChunk>> lists = new ArrayList<List<ScoredChunk>>();
        lists.add(bm25Hits);
        boolean degraded = false;
        String reason = null;
        int vectorHitCount = 0;
        if (vectorAvailable) {
            try {
                double[] queryVector = embeddingClient.embed(query);
                List<ScoredChunk> vectorHits = filterByCrop(vectorIndex.search(queryVector, TOP_K_EACH), cropType);
                vectorHitCount = vectorHits.size();
                lists.add(vectorHits);
            } catch (EmbeddingUnavailableException error) {
                degraded = true;
                reason = DEGRADED_EMBEDDING;
            }
        } else {
            degraded = true;
            reason = DEGRADED_EMBEDDING;
        }
        List<ScoredChunk> fused = fusion.fuse(lists, RRF_K, topN);
        return new RetrievalResult(fused, degraded, reason, bm25Hits.size(), vectorHitCount);
    }

    /**
     * 低分判定：无命中、或**完全没有关键词证据**、或融合分数低于阈值。
     * 仅靠向量召回的语义漂移不足以支撑专业结论，此时应改写查询或拒答。
     */
    public boolean isLowScore(RetrievalResult result) {
        if (result == null || result.getItems().isEmpty()) {
            return true;
        }
        if (result.getBm25HitCount() == 0) {
            return true;
        }
        return result.getTopScore() < MIN_SCORE;
    }

    private List<ScoredChunk> filterByCrop(List<ScoredChunk> hits, String cropType) {
        if (cropType == null || cropType.trim().isEmpty() || hits == null) {
            return hits == null ? new ArrayList<ScoredChunk>() : hits;
        }
        List<ScoredChunk> filtered = new ArrayList<ScoredChunk>();
        for (ScoredChunk hit : hits) {
            String chunkCrop = hit.getChunk().getCropType();
            if (chunkCrop == null || chunkCrop.trim().isEmpty() || chunkCrop.contains(cropType)) {
                filtered.add(hit);
            }
        }
        return filtered;
    }
}
