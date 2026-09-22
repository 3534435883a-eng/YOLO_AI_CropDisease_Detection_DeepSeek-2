package com.example.Ece.agent.rag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** ingest 结果报告：accepted/rejected/chunksWritten/chunksSkipped/是否降级/拒绝原因/各阶段耗时。 */
public class IngestReport {

    private final String sourceCode;
    private int accepted;
    private int rejected;
    private int chunksNewlyWritten;
    private int chunksSkipped;
    private int chunksRemoved;
    private int chunksTotal;
    private boolean embeddingDegraded;
    private final List<String> rejectedReasons = new ArrayList<String>();
    private String embeddingModel = "";
    private long deleteMillis;
    private long embedMillis;
    private long writeMillis;

    public IngestReport(String sourceCode) {
        this.sourceCode = sourceCode;
    }

    public void accept() { accepted++; }

    public void reject(String reason) {
        rejected++;
        rejectedReasons.add(reason);
    }

    public void addNewlyWritten(int count) { chunksNewlyWritten += count; }

    public void addSkipped(int count) { chunksSkipped += count; }

    public void addRemoved(int count) { chunksRemoved += count; }

    public int getChunksRemoved() { return chunksRemoved; }

    /** 以下三个耗时用于定位 ingest 的真实瓶颈（不要靠猜：实测 338 块里最大的一块不是向量化）。 */
    public void addDeleteMillis(long millis) { deleteMillis += millis; }

    public void addEmbedMillis(long millis) { embedMillis += millis; }

    public void addWriteMillis(long millis) { writeMillis += millis; }

    public long getDeleteMillis() { return deleteMillis; }

    public long getEmbedMillis() { return embedMillis; }

    public long getWriteMillis() { return writeMillis; }

    public void markDegraded(String model) {
        this.embeddingDegraded = true;
        this.embeddingModel = model == null ? "" : model;
    }

    public void setEmbeddingModel(String model) { this.embeddingModel = model == null ? "" : model; }

    public String getSourceCode() { return sourceCode; }

    public int getAccepted() { return accepted; }

    public int getRejected() { return rejected; }

    public int getChunksNewlyWritten() { return chunksNewlyWritten; }

    public int getChunksTotal() { return chunksTotal; }

    public void setChunksTotal(int total) { this.chunksTotal = total; }

    public int getChunksSkipped() { return chunksSkipped; }

    public boolean isEmbeddingDegraded() { return embeddingDegraded; }

    public String getEmbeddingModel() { return embeddingModel; }

    public List<String> getRejectedReasons() { return Collections.unmodifiableList(rejectedReasons); }
}
