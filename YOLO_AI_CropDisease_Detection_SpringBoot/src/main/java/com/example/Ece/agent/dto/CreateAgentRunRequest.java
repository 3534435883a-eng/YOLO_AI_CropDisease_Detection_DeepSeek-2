package com.example.Ece.agent.dto;

public class CreateAgentRunRequest {
    private Long greenhouseId;
    private String cropCode;
    private String cropName;
    private String runName;
    private Long seed;
    private String operatorUsername;

    public Long getGreenhouseId() { return greenhouseId; }
    public void setGreenhouseId(Long greenhouseId) { this.greenhouseId = greenhouseId; }
    public String getCropCode() { return cropCode; }
    public void setCropCode(String cropCode) { this.cropCode = cropCode; }
    public String getCropName() { return cropName; }
    public void setCropName(String cropName) { this.cropName = cropName; }
    public String getRunName() { return runName; }
    public void setRunName(String runName) { this.runName = runName; }
    public Long getSeed() { return seed; }
    public void setSeed(Long seed) { this.seed = seed; }
    public String getOperatorUsername() { return operatorUsername; }
    public void setOperatorUsername(String operatorUsername) { this.operatorUsername = operatorUsername; }
}
