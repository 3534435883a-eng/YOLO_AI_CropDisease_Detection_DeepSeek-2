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

    public RetrievalResult(List<ScoredChunk> items, boolean degraded, String degradedReason,
                           int bm25HitCount, int vectorHitCount) {
        this.items = items == null ? new ArrayList<ScoredChunk>() : items;
        this.degraded = degraded;
        this.degradedReason = degradedReason;
        this.bm25HitCount = bm25HitCount;
        this.vectorHitCount = vectorHitCount;
    }

    public List<ScoredChunk> getItems() { return Collections.unmodifiableList(items); }

    public boolean isDegraded() { return degraded; }

    public String getDegradedReason() { return degradedReason; }

    public int getBm25HitCount() { return bm25HitCount; }

    public int getVectorHitCount() { return vectorHitCount; }

    public double getTopScore() { return items.isEmpty() ? 0.0 : items.get(0).getScore(); }
}
