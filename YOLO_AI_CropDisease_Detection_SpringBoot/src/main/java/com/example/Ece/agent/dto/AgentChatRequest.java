package com.example.Ece.agent.dto;

/** 智能体会话请求。 */
public class AgentChatRequest {

    private String sessionId;
    private String question;
    private String crop;
    private Long runId;

    public String getSessionId() { return sessionId; }

    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getQuestion() { return question; }

    public void setQuestion(String question) { this.question = question; }

    public String getCrop() { return crop; }

    public void setCrop(String crop) { this.crop = crop; }

    public Long getRunId() { return runId; }

    public void setRunId(Long runId) { this.runId = runId; }
}
