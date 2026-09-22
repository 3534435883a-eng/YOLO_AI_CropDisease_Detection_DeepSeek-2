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

    public double getTopScore() { return items.isEmpty() ? 0.0 : items.get(0).getScore(); }
}
