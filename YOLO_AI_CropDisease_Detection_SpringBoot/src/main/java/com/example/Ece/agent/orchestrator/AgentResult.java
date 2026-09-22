package com.example.Ece.agent.orchestrator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** 一次智能体会话的结果。 */
public class AgentResult {

    public enum Status { DONE, REFUSED, ERROR }

    private final String answer;
    private final List<Map<String, Object>> citations;
    private final List<AgentStepEvent> events;
    private final int steps;
    private final Status status;

    public AgentResult(String answer, List<Map<String, Object>> citations, List<AgentStepEvent> events,
                       int steps, Status status) {
        this.answer = answer;
        this.citations = citations == null ? new ArrayList<Map<String, Object>>() : citations;
        this.events = events == null ? new ArrayList<AgentStepEvent>() : events;
        this.steps = steps;
        this.status = status;
    }

    public String getAnswer() { return answer; }

    public List<Map<String, Object>> getCitations() { return Collections.unmodifiableList(citations); }

    public List<AgentStepEvent> getEvents() { return Collections.unmodifiableList(events); }

    public int getSteps() { return steps; }

    public Status getStatus() { return status; }
}
