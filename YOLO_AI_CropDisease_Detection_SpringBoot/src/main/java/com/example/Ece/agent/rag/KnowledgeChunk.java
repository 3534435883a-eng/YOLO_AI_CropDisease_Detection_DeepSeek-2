package com.example.Ece.agent.rag;

/** 知识库切块：来源固定为遗留 disease 表，内容按字段切分。 */
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

    public KnowledgeChunk(String sourceTable, long sourceId, String cropType, String diseaseName,
                          FieldType fieldType, int chunkNo, int startOffset, String content, String contentHash) {
        this.sourceTable = sourceTable;
        this.sourceId = sourceId;
        this.cropType = cropType;
        this.diseaseName = diseaseName;
        this.fieldType = fieldType;
        this.chunkNo = chunkNo;
        this.startOffset = startOffset;
        this.content = content;
        this.contentHash = contentHash;
    }

    public String getSourceTable() { return sourceTable; }

    public long getSourceId() { return sourceId; }

    public String getCropType() { return cropType; }

    public String getDiseaseName() { return diseaseName; }

    public FieldType getFieldType() { return fieldType; }

    public int getChunkNo() { return chunkNo; }

    public int getStartOffset() { return startOffset; }

    public String getContent() { return content; }

    public String getContentHash() { return contentHash; }
}
