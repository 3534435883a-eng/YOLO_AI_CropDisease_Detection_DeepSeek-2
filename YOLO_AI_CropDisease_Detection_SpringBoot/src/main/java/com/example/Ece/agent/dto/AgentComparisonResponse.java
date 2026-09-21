package com.example.Ece.agent.dto;

import java.util.List;
import java.util.Map;

public class AgentComparisonResponse {
    private List<Map<String, Object>> items;
    private Map<String, Object> baseline;
    private Map<String, Object> strategy;
    private List<Map<String, Object>> resources;

    public List<Map<String, Object>> getItems() { return items; }
    public void setItems(List<Map<String, Object>> items) { this.items = items; }
    public Map<String, Object> getBaseline() { return baseline; }
    public void setBaseline(Map<String, Object> baseline) { this.baseline = baseline; }
    public Map<String, Object> getStrategy() { return strategy; }
    public void setStrategy(Map<String, Object> strategy) { this.strategy = strategy; }
    public List<Map<String, Object>> getResources() { return resources; }
    public void setResources(List<Map<String, Object>> resources) { this.resources = resources; }
}
