package com.example.Ece.agent.guard;

import com.example.Ece.agent.rag.KnowledgeChunk;
import com.example.Ece.agent.rag.ScoredChunk;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuardrailServiceTest {

    private final GuardrailService guard = new GuardrailService();

    private ScoredChunk citation() {
        return new ScoredChunk(new KnowledgeChunk("disease", 1L, "番茄", "早疫病",
                KnowledgeChunk.FieldType.CONTROL, 0, 0, "可选用代森锰锌", "h1"), 0.03, 1);
    }

    @Test
    void allowsAnswerWithCitations() {
        GuardrailCheck check = guard.check("建议通风降湿。[1]", Arrays.asList(citation()), false);
        assertTrue(check.isAllowed());
    }

    @Test
    void rejectsProfessionalClaimWithoutCitation() {
        GuardrailCheck check = guard.check("喷施戊唑醇 3000 倍液即可。", new ArrayList<ScoredChunk>(), false);
        assertFalse(check.isAllowed());
        assertEquals("NO_EVIDENCE", check.getReason());
    }

    @Test
    void forcesManualConfirmForPesticideMentions() {
        GuardrailCheck check = guard.check("可选用代森锰锌防治。[1]", Arrays.asList(citation()), false);
        assertTrue(check.isAllowed());
        assertTrue(check.getRewrittenAnswer().contains("需人工确认"));
    }

    @Test
    void disclosesDegradedRetrieval() {
        GuardrailCheck check = guard.check("建议通风。[1]", Arrays.asList(citation()), true);
        assertTrue(check.getRewrittenAnswer().contains("降级"));
    }

    @Test
    void forbidsDeviceExecutionClaims() {
        GuardrailCheck check = guard.check("已自动开启通风设备。[1]", Arrays.asList(citation()), false);
        assertFalse(check.isAllowed());
        assertEquals("AUTO_EXECUTION_CLAIM", check.getReason());
    }

    @Test
    void rewritesSimulatedValuesPresentedAsMeasured() {
        GuardrailCheck check = guard.check("实测棚内温度为 31℃。[1]", Arrays.asList(citation()), false);
        assertTrue(check.isAllowed());
        assertFalse(check.getRewrittenAnswer().contains("实测"));
        assertTrue(check.getRewrittenAnswer().contains("推演显示"));
    }
}
