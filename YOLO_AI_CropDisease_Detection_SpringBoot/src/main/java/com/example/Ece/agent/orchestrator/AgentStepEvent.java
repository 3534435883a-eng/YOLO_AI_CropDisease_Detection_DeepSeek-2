package com.example.Ece.agent.orchestrator;

import java.util.LinkedHashMap;
import java.util.Map;

/** 编排过程事件，经 SSE 推给前端做"步骤时间线"。 */
public class AgentStepEvent {

    private final String type;
    private final int stepNo;
    private final String toolName;
    private final String message;
    private final Map<String, Object> payload;

    public AgentStepEvent(String type, int stepNo, String toolName, String message, Map<String, Object> payload) {
        this.type = type;
        this.stepNo = stepNo;
        this.toolName = toolName;
        this.message = message;
        this.payload = payload == null ? new LinkedHashMap<String, Object>() : payload;
    }

    public String getType() { return type; }

    public int getStepNo() { return stepNo; }

    public String getToolName() { return toolName; }

    public String getMessage() { return message; }

    public Map<String, Object> getPayload() { return payload; }
}
