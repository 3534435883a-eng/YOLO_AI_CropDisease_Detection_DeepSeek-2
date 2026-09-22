package com.example.Ece.agent.rag;

import java.util.Map;

/** 待入库的一条知识记录。 */
public class IngestRecord {

    private final String sourceTable;
    private final long sourceId;
    private final String cropType;
    private final String diseaseName;
    private final Map<KnowledgeChunk.FieldType, String> fields;

    public IngestRecord(long sourceId, String cropType, String diseaseName,
                        Map<KnowledgeChunk.FieldType, String> fields) {
        this("disease", sourceId, cropType, diseaseName, fields);
    }

    public IngestRecord(String sourceTable, long sourceId, String cropType, String diseaseName,
                        Map<KnowledgeChunk.FieldType, String> fields) {
        this.sourceTable = sourceTable == null || sourceTable.trim().isEmpty() ? "disease" : sourceTable.trim();
        this.sourceId = sourceId;
        this.cropType = cropType;
        this.diseaseName = diseaseName;
        this.fields = fields;
    }

    /** 来源表名：多来源知识库用它区分不同数据集（不再硬编码 disease）。 */
    public String getSourceTable() { return sourceTable; }

    public long getSourceId() { return sourceId; }

    public String getCropType() { return cropType; }

    public String getDiseaseName() { return diseaseName; }

    public Map<KnowledgeChunk.FieldType, String> getFields() { return fields; }
}
