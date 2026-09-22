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

    /**
     * 建议语气不得被判越权。实测真实端到端时，"建议自动开启通风"这类正常建议
     * 曾与"已自动开启"同等对待，导致整段有依据的回答（21 秒生成、5 条引用）被丢弃成拒答。
     */
    @Test
    void keepsAdvisoryWordingButStripsExecutionSense() {
        GuardrailCheck check = guard.check("棚内湿度偏高，建议自动开启通风排湿。[1]", Arrays.asList(citation()), false);
        assertTrue(check.isAllowed(), "建议语气不应被判为越权声明");
        assertFalse(check.getRewrittenAnswer().contains("自动开启"), "完成态动作词应被改写成建议语气");
        assertTrue(check.getRewrittenAnswer().contains("建议开启通风排湿"));
        assertTrue(check.getRewrittenAnswer().contains("不直接执行设备动作"), "改写后必须披露本系统不执行设备动作");
    }

    /** "已开启/已执行"这类完成态表述即使不含"自动"，也必须被改写成建议语气并加声明。 */
    @Test
    void neutralizesCompletedTenseActionWording() {
        GuardrailCheck check = guard.check("系统已开启补光，建议继续观察。[1]", Arrays.asList(citation()), false);
        assertTrue(check.isAllowed());
        assertFalse(check.getRewrittenAnswer().contains("已开启"));
        assertTrue(check.getRewrittenAnswer().contains("建议开启补光"));
        assertTrue(check.getRewrittenAnswer().contains("不直接执行设备动作"));
    }

    /** 真正"替你做了"的完成态声明仍然必须整段拒绝。 */
    @Test
    void stillRejectsExplicitAlreadyDoneClaims() {
        GuardrailCheck check = guard.check("已经自动为你完成灌溉。[1]", Arrays.asList(citation()), false);
        assertFalse(check.isAllowed());
        assertEquals("AUTO_EXECUTION_CLAIM", check.getReason());
    }

    void rewritesSimulatedValuesPresentedAsMeasured() {
        GuardrailCheck check = guard.check("实测棚内温度为 31℃。[1]", Arrays.asList(citation()), false);
        assertTrue(check.isAllowed());
        assertFalse(check.getRewrittenAnswer().contains("实测"));
        assertTrue(check.getRewrittenAnswer().contains("推演显示"));
    }
}
