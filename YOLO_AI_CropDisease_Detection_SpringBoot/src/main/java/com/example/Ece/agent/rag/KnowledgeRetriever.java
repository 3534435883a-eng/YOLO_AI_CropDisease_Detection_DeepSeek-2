package com.example.Ece.agent.rag;

import org.springframework.beans.factory.annotation.Autowired;
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
    /**
     * 语料级查询词覆盖率下限（IDF 加权）。E2 判据：问题用词整体上确实落在语料里。
     *
     * <p>实测负样本最高 0.31、真实提问可达 0.69，取 0.45 留出余量。</p>
     */
    public static final double MIN_COVERAGE = 0.45;

    /**
     * Top 块内查询词**共现**个数下限。E1 判据：至少两个不同的查询词出现在同一个知识块里。
     *
     * <p>一个词只能说明"提到过"，两个词共现才说明"在讲这件事"。</p>
     */
    public static final int MIN_CHUNK_TERMS = 2;
    public static final String DEGRADED_EMBEDDING = "EMBEDDING_UNAVAILABLE";

    private final EmbeddingClient embeddingClient;
    /** 作物别名词典；为 null 时不做归一化（单元测试只需 BM25/向量时可以不传）。 */
    private final KnowledgeEntityLexicon entityLexicon;
    private final Bm25Index bm25Index = new Bm25Index();
    private final VectorIndex vectorIndex = new VectorIndex();
    private final RrfFusion fusion = new RrfFusion();
    private boolean vectorAvailable = false;

    /** 单元测试用：不注入别名词典（不做归一化与扩展）。 */
    public KnowledgeRetriever(EmbeddingClient embeddingClient) {
        this(embeddingClient, null);
    }

    /**
     * 生产用构造器。
     *
     * <p><b>必须显式 {@code @Autowired}</b>：类里有两个构造器时，Spring 不会自动选择，
     * 而是去找无参构造器 → 报 "No default constructor found" 导致**应用整体启动失败**（实测踩过）。
     * 教训：改构造器后必须跑包含 {@code EceTests}（全上下文加载）的全量测试，只跑单测会漏掉这类错误。</p>
     */
    @Autowired
    public KnowledgeRetriever(EmbeddingClient embeddingClient, KnowledgeEntityLexicon entityLexicon) {
        this.embeddingClient = embeddingClient;
        this.entityLexicon = entityLexicon;
    }

    public void rebuild(List<KnowledgeChunk> chunks) {
        rebuild(chunks, null);
    }

    /**
     * 重建内存索引。
     *
     * @param storedEmbeddings 与 {@code chunks} 一一对应的**已入库向量**（来自 agent_knowledge_chunk.embedding）。
     *                         可用时直接复用，避免每次启动把几百个块重新向量化一遍；
     *                         为 null、长度不符或含空向量时退回现场向量化。
     */
    public void rebuild(List<KnowledgeChunk> chunks, List<double[]> storedEmbeddings) {
        bm25Index.rebuild(chunks);
        List<KnowledgeChunk> indexed = chunks == null ? new ArrayList<KnowledgeChunk>() : chunks;
        List<double[]> vectors = reusableVectors(indexed, storedEmbeddings);
        if (vectors == null) {
            vectors = embedAll(indexed);
        }
        vectorAvailable = vectors != null;
        if (vectorAvailable) {
            vectorIndex.rebuild(indexed, vectors);
        } else {
            vectorIndex.rebuild(new ArrayList<KnowledgeChunk>(), new ArrayList<double[]>());
        }
    }

    private List<double[]> reusableVectors(List<KnowledgeChunk> indexed, List<double[]> storedEmbeddings) {
        if (indexed.isEmpty() || storedEmbeddings == null || storedEmbeddings.size() != indexed.size()) {
            return null;
        }
        for (double[] vector : storedEmbeddings) {
            if (vector == null || vector.length == 0) {
                return null;
            }
        }
        return new ArrayList<double[]>(storedEmbeddings);
    }

    /** 现场向量化；任一失败即整体放弃向量召回（返回 null），由调用方降级为纯关键词检索。 */
    private List<double[]> embedAll(List<KnowledgeChunk> indexed) {
        if (indexed.isEmpty()) {
            return null;
        }
        List<double[]> vectors = new ArrayList<double[]>();
        for (KnowledgeChunk chunk : indexed) {
            try {
                vectors.add(embeddingClient.embed(chunk.getContent()));
            } catch (EmbeddingUnavailableException error) {
                return null;
            }
        }
        return vectors;
    }

    public RetrievalResult retrieve(String query, String cropType, int topN) {
        // 先归一化：作物字段可能是农户别名（"土豆"），而知识库里写的是"马铃薯"；
        // 不归一化时 filterByCrop 的 contains 判断为假 → 实测 2/2 零召回。
        String effectiveCrop = entityLexicon == null ? cropType : entityLexicon.canonicalizeCrop(cropType);
        // 再扩展查询：问题里出现别名时补上规范作物名（知识块头部就带"作物：马铃薯"，补上后才对得上）。
        String effectiveQuery = entityLexicon == null ? query : entityLexicon.expandQuery(query);
        List<ScoredChunk> bm25Hits = filterByCrop(bm25Index.search(effectiveQuery, TOP_K_EACH), effectiveCrop);
        List<List<ScoredChunk>> lists = new ArrayList<List<ScoredChunk>>();
        lists.add(bm25Hits);
        boolean degraded = false;
        String reason = null;
        int vectorHitCount = 0;
        if (vectorAvailable) {
            try {
                double[] queryVector = embeddingClient.embed(effectiveQuery);
                List<ScoredChunk> vectorHits = filterByCrop(vectorIndex.search(queryVector, TOP_K_EACH), effectiveCrop);
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
        double coverage = bm25Index.queryCoverage(effectiveQuery);
        double maxChunkCoverage = 0.0;
        int maxChunkMatchedTerms = 0;
        for (ScoredChunk item : fused) {
            int matchedInChunk = bm25Index.chunkMatchedTermCount(effectiveQuery, item.getChunk());
            if (matchedInChunk > maxChunkMatchedTerms) {
                maxChunkMatchedTerms = matchedInChunk;
            }
            double chunkCov = bm25Index.chunkCoverage(effectiveQuery, item.getChunk());
            if (chunkCov > maxChunkCoverage) {
                maxChunkCoverage = chunkCov;
            }
        }
        return new RetrievalResult(fused, degraded, reason, bm25Hits.size(), vectorHitCount, coverage,
                maxChunkCoverage, maxChunkMatchedTerms);
    }

    /**
     * 低分判定（是否需要改写或拒答）。满足任一"有依据"的条件即不低分：
     *
     * <ul>
     *   <li><b>E1 证据共现</b>：Top 块里有 ≥{@link #MIN_CHUNK_TERMS} 个不同的查询词。</li>
     *   <li><b>E2 语料覆盖</b>：查询词的 IDF 加权覆盖率 ≥{@link #MIN_COVERAGE}。</li>
     * </ul>
     *
     * <p>前置条件：必须有 BM25 命中。仅靠向量召回的语义相近不足以支撑专业结论。</p>
     *
     * <p><b>被实测推翻的两个判据（记录在此以免回退）：</b></p>
     * <ol>
     *   <li><i>用 RRF 融合分当置信阈值不可行。</i>RRF 按构造只保留排名、丢弃分数量级，
     *       实测负样本 0.0164~0.0317 与真实提问 0.0300~0.0328 几乎完全重叠，任何阈值都无意义。</li>
     *   <li><i>只看语料级覆盖率会误杀正常提问。</i>覆盖率为长度归一量：长问句被稀释，
     *       且农户说"土豆"、语料写"马铃薯"时未登录词吃到最重惩罚。实测阈值 0.30 误拒 18.8%
     *       的口语化提问（如"土豆叶尖叶缘先烂，边上有一圈白霉"覆盖率仅 0.21）。</li>
     * </ol>
     *
     * <p>触发低分不等于拒答：上层先做查询改写重试，改写后仍低分才拒答。因此判据宁严勿宽——
     * 多一次改写代价很小，用错证据作答代价很大。</p>
     */
    public boolean isLowScore(RetrievalResult result) {
        if (result == null || result.getItems().isEmpty()) {
            return true;
        }
        if (result.getBm25HitCount() == 0) {
            return true;
        }
        boolean evidenceCoOccurs = result.getMaxChunkMatchedTerms() >= MIN_CHUNK_TERMS;
        boolean corpusCovered = result.getQueryCoverage() >= MIN_COVERAGE;
        return !(evidenceCoOccurs || corpusCovered);
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
