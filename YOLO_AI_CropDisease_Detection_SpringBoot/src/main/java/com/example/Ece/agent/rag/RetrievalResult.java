package com.example.Ece.agent.rag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 一次检索的完整结果，含降级标记与两路召回计数（供审计与评测使用）。 */
public class RetrievalResult {

    private final List<ScoredChunk> items;
    private final boolean degraded;
    private final String degradedReason;
    private final int bm25HitCount;
    private final int vectorHitCount;
    private final double queryCoverage;
    private final double maxChunkCoverage;
    private final int maxChunkMatchedTerms;


    /**
     * Top-1 块的**原始向量余弦相似度**（未过 RRF）。
     *
     * <p>RRF 按构造只保留排名、丢弃分数量级，所以融合分不能当置信度用。
     * 但**原始余弦**没有被丢弃——它只是没被暴露出来。本字段用于测量
     * "语义相近度能否区分有依据与无依据"。向量服务不可用时为 0。</p>
     */
    private double topVectorSimilarity;

    /**
     * 问题里点名的病/虫**在语料中不存在**时的那个词（否则为 null）。
     *
     * <p>由检索层在召回后算出（见 {@link UnknownEntityDetector}）。与覆盖率、融合分这类
     * 统计信号不同，它是个**二值事实**：问题问的那个病/虫，本作物的语料里有没有。
     * 难负样本（{@code 玉米黄秆虫怎么防治}）能靠「玉米」+「防治」两个通用词通过
     * 共现判据，但「黄秆虫」在语料里根本不存在——这正是本条要捕捉的。</p>
     */
    private String unknownEntityToken;

    public RetrievalResult(List<ScoredChunk> items, boolean degraded, String degradedReason,
                           int bm25HitCount, int vectorHitCount, double queryCoverage, double maxChunkCoverage,
                           int maxChunkMatchedTerms) {
        this.items = items == null ? new ArrayList<ScoredChunk>() : items;
        this.degraded = degraded;
        this.degradedReason = degradedReason;
        this.bm25HitCount = bm25HitCount;
        this.vectorHitCount = vectorHitCount;
        this.queryCoverage = queryCoverage;
        this.maxChunkCoverage = maxChunkCoverage;
        this.maxChunkMatchedTerms = maxChunkMatchedTerms;
    }

    public List<ScoredChunk> getItems() { return Collections.unmodifiableList(items); }

    public boolean isDegraded() { return degraded; }

    public String getDegradedReason() { return degradedReason; }

    public int getBm25HitCount() { return bm25HitCount; }

    public int getVectorHitCount() { return vectorHitCount; }

    /** 查询词覆盖率（IDF 加权，0~1）。 */
    public double getQueryCoverage() { return queryCoverage; }

    /** Top 块中最高的逐块查询词覆盖率（0~1）：证据是否真正共现。 */
    public double getMaxChunkCoverage() { return maxChunkCoverage; }

    /** Top 块中最多的**共现**查询词个数（绝对数量）：拒答判据的主信号。 */
    public int getMaxChunkMatchedTerms() { return maxChunkMatchedTerms; }


    /** Top-1 块的原始向量余弦（见字段说明）。 */
    public double getTopVectorSimilarity() { return topVectorSimilarity; }

    /** 由检索层在融合后回填：Top-1 块在向量结果里的原始余弦。 */
    public void setTopVectorSimilarity(double value) { this.topVectorSimilarity = value; }

    /** 问题里点名但语料中不存在的病/虫（null 表示未发现此类问题）。 */
    public String getUnknownEntityToken() { return unknownEntityToken; }

    /** 由检索层在召回后回填：见字段说明。 */
    public void setUnknownEntityToken(String value) { this.unknownEntityToken = value; }

    public double getTopScore() { return items.isEmpty() ? 0.0 : items.get(0).getScore(); }
}
