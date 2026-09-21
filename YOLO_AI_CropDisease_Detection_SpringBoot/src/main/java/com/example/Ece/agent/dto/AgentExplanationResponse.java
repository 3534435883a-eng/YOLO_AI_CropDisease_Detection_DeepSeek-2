package com.example.Ece.agent.dto;

public class AgentExplanationResponse {
    private String content;
    private String source;
    private String runId;

    public AgentExplanationResponse() {
    }

    public AgentExplanationResponse(String content, String source, String runId) {
        this.content = content;
        this.source = source;
        this.runId = runId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }
}
