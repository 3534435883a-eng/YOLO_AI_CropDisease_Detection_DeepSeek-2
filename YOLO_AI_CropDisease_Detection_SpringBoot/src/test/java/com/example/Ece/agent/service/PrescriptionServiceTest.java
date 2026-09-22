package com.example.Ece.agent.service;

import com.example.Ece.agent.engine.TomatoDecisionPolicy;
import com.example.Ece.agent.engine.TomatoSimulationEngine;
import com.example.Ece.agent.model.DecisionPlan;
import com.example.Ece.agent.model.SimulationState;
import com.example.Ece.agent.rag.KnowledgeChunk;
import com.example.Ece.agent.rag.ScoredChunk;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrescriptionServiceTest {

    private final PrescriptionService service = new PrescriptionService();
    private final TomatoSimulationEngine engine = new TomatoSimulationEngine();
    private final TomatoDecisionPolicy policy = new TomatoDecisionPolicy();

    private SimulationState state(double temperature, double humidity, double soil, double light) {
        return engine.evaluate(LocalDateTime.of(2026, 9, 21, 12, 0), temperature, humidity, soil, 600.0, light, 6.2);
    }

    private List<ScoredChunk> citations() {
        return Arrays.asList(new ScoredChunk(new KnowledgeChunk("disease", 1L, "番茄", "早疫病",
                KnowledgeChunk.FieldType.CONTROL, 0, 0, "高温期加强通风", "h1"), 0.03, 1));
    }

    @Test
    void draftsActionsAndRequiresManualConfirmForDeviceChanges() {
        SimulationState hot = state(33.0, 90.0, 30.0, 100.0);
        DecisionPlan plan = policy.decide(hot);
        Prescription prescription = service.draft(hot, plan, citations());
        assertFalse(prescription.getActions().isEmpty(), "高温干旱场景应给出设备动作");
        assertTrue(prescription.isRequiresManualConfirm(), "任何设备动作都必须人工确认");
        assertTrue(prescription.getConclusion().contains(plan.getSummary()));
    }

    @Test
    void carriesRiskCautionForHighRiskEnvironment() {
        // 34℃ + 95% 湿度 + 干旱 + 弱光：温度/湿度/土壤三项风险均打满，总风险 >= 70 判为 HIGH
        SimulationState hot = state(34.0, 95.0, 25.0, 100.0);
        DecisionPlan plan = policy.decide(hot);
        Prescription prescription = service.draft(hot, plan, citations());
        assertTrue("HIGH".equalsIgnoreCase(plan.getRiskLevel()), "该环境应判为高风险");
        assertTrue(join(prescription.getCautions()).contains("风险等级为高"));
    }

    @Test
    void warnsWhenNoCitationIsAvailable() {
        SimulationState mild = state(24.0, 70.0, 60.0, 700.0);
        DecisionPlan plan = policy.decide(mild);
        Prescription prescription = service.draft(mild, plan, null);
        assertTrue(join(prescription.getCautions()).contains("未附带知识库引用"));
    }

    private String join(List<String> items) {
        StringBuilder builder = new StringBuilder();
        for (String item : items) {
            builder.append(item).append(" | ");
        }
        return builder.toString();
    }
}
