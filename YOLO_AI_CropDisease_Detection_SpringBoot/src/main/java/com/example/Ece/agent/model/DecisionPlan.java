package com.example.Ece.agent.model;

import java.util.Collections;
import java.util.List;

/** Structured deterministic policy output, kept separate from persistence and AI explanation. */
public class DecisionPlan {
    private final List<DeviceCommand> commands;
    private final String summary;
    private final String riskLevel;

    public DecisionPlan(List<DeviceCommand> commands, String summary, String riskLevel) {
        this.commands = Collections.unmodifiableList(commands);
        this.summary = summary;
        this.riskLevel = riskLevel;
    }

    public List<DeviceCommand> getCommands() {
        return commands;
    }

    public String getSummary() {
        return summary;
    }

    public String getRiskLevel() {
        return riskLevel;
    }
}
