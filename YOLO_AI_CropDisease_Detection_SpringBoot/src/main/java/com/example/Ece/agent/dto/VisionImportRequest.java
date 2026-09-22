package com.example.Ece.agent.dto;

/** 识别记录导入请求。作物正常由记录的 kind/weight 判定，crop 仅在判定不出时兜底。 */
public class VisionImportRequest {

    private String sourceType;
    private Long sourceRecordId;
    private String crop;

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public Long getSourceRecordId() {
        return sourceRecordId;
    }

    public void setSourceRecordId(Long sourceRecordId) {
        this.sourceRecordId = sourceRecordId;
    }

    public String getCrop() {
        return crop;
    }

    public void setCrop(String crop) {
        this.crop = crop;
    }
}