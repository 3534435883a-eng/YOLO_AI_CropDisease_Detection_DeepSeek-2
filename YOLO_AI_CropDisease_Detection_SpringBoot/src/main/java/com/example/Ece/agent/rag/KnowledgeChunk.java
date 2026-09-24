package com.example.Ece.agent.rag;

/** 可检索知识块及其来源登记。 */
public class KnowledgeChunk {

    public enum FieldType { SYMPTOM, CAUSE, CONTROL, OTHER }

    private final String sourceTable;
    private final long sourceId;
    private final String cropType;
    private final String diseaseName;
    private final FieldType fieldType;
    private final int chunkNo;
    private final int startOffset;
    private final String content;
    private final String contentHash;
    private final String sourceCode;
    private final String sourceName;
    private final String sourceType;
    private final String sourceUrl;
    private final String sourceVersion;

    public KnowledgeChunk(String sourceTable, long sourceId, String cropType, String diseaseName,
                          FieldType fieldType, int chunkNo, int startOffset, String content, String contentHash) {
        this(sourceTable, sourceId, cropType, diseaseName, fieldType, chunkNo, startOffset, content, contentHash,
                null, null, null, null, null);
    }

    public KnowledgeChunk(String sourceTable, long sourceId, String cropType, String diseaseName,
                          FieldType fieldType, int chunkNo, int startOffset, String content, String contentHash,
                          String sourceCode, String sourceName, String sourceType, String sourceUrl,
                          String sourceVersion) {
        this.sourceTable = sourceTable;
        this.sourceId = sourceId;
        this.cropType = cropType;
        this.diseaseName = diseaseName;
        this.fieldType = fieldType;
        this.chunkNo = chunkNo;
        this.startOffset = startOffset;
        this.content = content;
        this.contentHash = contentHash;
        this.sourceCode = sourceCode;
        this.sourceName = sourceName;
        this.sourceType = sourceType;
        this.sourceUrl = sourceUrl;
        this.sourceVersion = sourceVersion;
    }

    public String getSourceTable() { return sourceTable; }

    public long getSourceId() { return sourceId; }

    public String getCropType() { return cropType; }

    public String getDiseaseName() { return diseaseName; }

    public FieldType getFieldType() { return fieldType; }

    public int getChunkNo() { return chunkNo; }

    public int getStartOffset() { return startOffset; }

    public String getContent() { return content; }

    /**
     * 去掉上下文头的正文。
     *
     * <p>检索的"证据共现"判据只看正文：头部里的作物名是**元数据而非证据**。
     * 实测反例——"如何给番茄施肥"因每个块头部都含"番茄"，共现判据拿到 {番茄, 施肥} 2 个词而放行，
     * 但知识库其实没有施肥知识。头部仍参与语料级覆盖率，因此病名类提问（如"番茄早疫病"）
     * 依然能凭"疫病/早疫"这类稀有词通过覆盖率判据。</p>
     */
    public String getBodyText() {
        if (content == null) {
            return "";
        }
        if (!content.startsWith("作物：") && !content.startsWith("病害：") && !content.startsWith("字段：")) {
            return content;
        }
        int end = content.indexOf('。');
        return end < 0 ? content : content.substring(end + 1);
    }

    public String getContentHash() { return contentHash; }

    public String getSourceCode() { return sourceCode; }

    public String getSourceName() { return sourceName; }

    public String getSourceType() { return sourceType; }

    public String getSourceUrl() { return sourceUrl; }

    public String getSourceVersion() { return sourceVersion; }
}
