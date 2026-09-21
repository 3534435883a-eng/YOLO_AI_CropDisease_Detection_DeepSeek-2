package com.example.Ece.agent.dto;

public class AgentRunResponse {
    private Long id;
    private String runCode;
    private String runName;
    private String greenhouseName;
    private String greenhouseCode;
    private String cropName;
    private String cropType;
    private String status;
    private Integer currentStep;
    private Integer totalSteps;
    private Integer progress;
    private String simulatedAt;
    private String createdAt;
    private String updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRunCode() { return runCode; }
    public void setRunCode(String runCode) { this.runCode = runCode; }
    public String getRunName() { return runName; }
    public void setRunName(String runName) { this.runName = runName; }
    public String getGreenhouseName() { return greenhouseName; }
    public void setGreenhouseName(String greenhouseName) { this.greenhouseName = greenhouseName; }
    public String getGreenhouseCode() { return greenhouseCode; }
    public void setGreenhouseCode(String greenhouseCode) { this.greenhouseCode = greenhouseCode; }
    public String getCropName() { return cropName; }
    public void setCropName(String cropName) { this.cropName = cropName; }
    public String getCropType() { return cropType; }
    public void setCropType(String cropType) { this.cropType = cropType; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getCurrentStep() { return currentStep; }
    public void setCurrentStep(Integer currentStep) { this.currentStep = currentStep; }
    public Integer getTotalSteps() { return totalSteps; }
    public void setTotalSteps(Integer totalSteps) { this.totalSteps = totalSteps; }
    public Integer getProgress() { return progress; }
    public void setProgress(Integer progress) { this.progress = progress; }
    public String getSimulatedAt() { return simulatedAt; }
    public void setSimulatedAt(String simulatedAt) { this.simulatedAt = simulatedAt; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
}
