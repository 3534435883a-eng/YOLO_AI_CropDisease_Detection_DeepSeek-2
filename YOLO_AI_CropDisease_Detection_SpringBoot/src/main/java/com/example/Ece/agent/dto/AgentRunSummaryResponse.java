package com.example.Ece.agent.dto;

import java.util.List;
import java.util.Map;

public class AgentRunSummaryResponse {
    private AgentRunResponse run;
    private Map<String, Object> currentState;
    private List<Map<String, Object>> metrics;
    private List<Map<String, Object>> devices;
    private List<Map<String, Object>> alerts;
    private List<Map<String, Object>> resources;
    private String strategySummary;
    private String updatedAt;

    public AgentRunResponse getRun() { return run; }
    public void setRun(AgentRunResponse run) { this.run = run; }
    public Map<String, Object> getCurrentState() { return currentState; }
    public void setCurrentState(Map<String, Object> currentState) { this.currentState = currentState; }
    public List<Map<String, Object>> getMetrics() { return metrics; }
    public void setMetrics(List<Map<String, Object>> metrics) { this.metrics = metrics; }
    public List<Map<String, Object>> getDevices() { return devices; }
    public void setDevices(List<Map<String, Object>> devices) { this.devices = devices; }
    public List<Map<String, Object>> getAlerts() { return alerts; }
    public void setAlerts(List<Map<String, Object>> alerts) { this.alerts = alerts; }
    public List<Map<String, Object>> getResources() { return resources; }
    public void setResources(List<Map<String, Object>> resources) { this.resources = resources; }
    public String getStrategySummary() { return strategySummary; }
    public void setStrategySummary(String strategySummary) { this.strategySummary = strategySummary; }
    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
}
