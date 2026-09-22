package com.example.Ece.agent.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 结构化处方：结论 + 动作 + 注意事项 + 是否必须人工确认。 */
public class Prescription {

    private final String conclusion;
    private final List<String> actions;
    private final List<String> cautions;
    private final boolean requiresManualConfirm;

    public Prescription(String conclusion, List<String> actions, List<String> cautions,
                        boolean requiresManualConfirm) {
        this.conclusion = conclusion;
        this.actions = actions == null ? new ArrayList<String>() : actions;
        this.cautions = cautions == null ? new ArrayList<String>() : cautions;
        this.requiresManualConfirm = requiresManualConfirm;
    }

    public String getConclusion() { return conclusion; }

    public List<String> getActions() { return Collections.unmodifiableList(actions); }

    public List<String> getCautions() { return Collections.unmodifiableList(cautions); }

    public boolean isRequiresManualConfirm() { return requiresManualConfirm; }
}
