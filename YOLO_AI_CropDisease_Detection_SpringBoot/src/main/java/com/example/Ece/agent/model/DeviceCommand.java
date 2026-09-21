package com.example.Ece.agent.model;

import java.math.BigDecimal;

/** A proposed device state for one virtual step. */
public class DeviceCommand {
    private final String deviceCode;
    private final boolean targetOn;
    private final String ruleCode;
    private final int priority;
    private final String summary;
    private final String resourceCode;
    private final BigDecimal resourceAmount;
    private final boolean urgent;

    public DeviceCommand(String deviceCode, boolean targetOn, String ruleCode, int priority,
                         String summary, String resourceCode, BigDecimal resourceAmount, boolean urgent) {
        this.deviceCode = deviceCode;
        this.targetOn = targetOn;
        this.ruleCode = ruleCode;
        this.priority = priority;
        this.summary = summary;
        this.resourceCode = resourceCode;
        this.resourceAmount = resourceAmount;
        this.urgent = urgent;
    }

    public String getDeviceCode() {
        return deviceCode;
    }

    public boolean isTargetOn() {
        return targetOn;
    }

    public String getRuleCode() {
        return ruleCode;
    }

    public int getPriority() {
        return priority;
    }

    public String getSummary() {
        return summary;
    }

    public String getResourceCode() {
        return resourceCode;
    }

    public BigDecimal getResourceAmount() {
        return resourceAmount;
    }

    public boolean isUrgent() {
        return urgent;
    }
}
