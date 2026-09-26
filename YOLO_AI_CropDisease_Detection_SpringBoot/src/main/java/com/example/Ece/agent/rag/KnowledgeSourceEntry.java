package com.example.Ece.agent.rag;

/**
 * 「来源登记 + 待入库记录」的成对结构，供人工整理的知识清单使用。
 *
 * <p>原先它是 {@code CuratedCropKnowledgeReader} 的嵌套类。2026-09-26 接入标准摘要条目时
 * 出现了第二个读者（{@link CitedKnowledgeReader}），若继续让后者返回
 * {@code CuratedCropKnowledgeReader.SourceEntry}，就会出现"标准表读出来的东西报着 curated 的名"，
 * 因此提为顶层类。语义上没有变化。</p>
 */
public final class KnowledgeSourceEntry {

    private final KnowledgeSource source;
    private final IngestRecord record;

    public KnowledgeSourceEntry(KnowledgeSource source, IngestRecord record) {
        this.source = source;
        this.record = record;
    }

    public KnowledgeSource getSource() { return source; }

    public IngestRecord getRecord() { return record; }
}
