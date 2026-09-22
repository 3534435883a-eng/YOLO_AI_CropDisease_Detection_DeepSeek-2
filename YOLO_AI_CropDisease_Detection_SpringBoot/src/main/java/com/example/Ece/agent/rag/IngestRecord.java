package com.example.Ece.agent.rag;

import java.util.Map;

/** 待入库的一条知识记录。 */
public class IngestRecord {

    private final long sourceId;
    private final String cropType;
    private final String diseaseName;
    private final Map<KnowledgeChunk.FieldType, String> fields;

    public IngestRecord(long sourceId, String cropType, String diseaseName,
                        Map<KnowledgeChunk.FieldType, String> fields) {
        this.sourceId = sourceId;
        this.cropType = cropType;
        this.diseaseName = diseaseName;
        this.fields = fields;
    }

    public long getSourceId() { return sourceId; }

    public String getCropType() { return cropType; }

    public String getDiseaseName() { return diseaseName; }

    public Map<KnowledgeChunk.FieldType, String> getFields() { return fields; }
}
