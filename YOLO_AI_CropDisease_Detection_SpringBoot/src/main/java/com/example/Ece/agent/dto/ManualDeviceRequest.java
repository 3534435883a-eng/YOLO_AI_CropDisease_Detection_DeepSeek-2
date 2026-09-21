package com.example.Ece.agent.dto;

public class ManualDeviceRequest {
    private String mode;
    private Boolean enabled;
    private String operatorUsername;

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public String getOperatorUsername() { return operatorUsername; }
    public void setOperatorUsername(String operatorUsername) { this.operatorUsername = operatorUsername; }
}
