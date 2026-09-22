package com.example.Ece.agent.tool;

import com.example.Ece.agent.rag.CitationFormatter;
import com.example.Ece.agent.rag.EmbeddingClient;
import com.example.Ece.agent.rag.EmbeddingUnavailableException;
import com.example.Ece.agent.rag.KnowledgeChunk;
import com.example.Ece.agent.rag.KnowledgeRetriever;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeSearchToolTest {

    private KnowledgeSearchTool toolWith(final boolean degraded) {
        KnowledgeRetriever retriever = new KnowledgeRetriever(new EmbeddingClient() {
            public double[] embed(String text) throws EmbeddingUnavailableException {
                if (degraded) {
                    throw new EmbeddingUnavailableException("down");
                }
                return new double[]{1.0, 0.0};
            }
        });
        retriever.rebuild(Arrays.asList(new KnowledgeChunk("disease", 1L, "番茄", "早疫病",
                KnowledgeChunk.FieldType.SYMPTOM, 0, 0, "番茄叶片出现褐色轮纹斑", "h1")));
        return new KnowledgeSearchTool(retriever, new CitationFormatter());
    }

    @Test
    void exposesMetadata() {
        KnowledgeSearchTool tool = toolWith(false);
        assertEquals("knowledge.search", tool.name());
        assertEquals(ToolPermission.READ_ONLY, tool.permission());
        assertTrue(tool.inputSchemaJson().contains("query"));
    }

    @Test
    void returnsCitationsAndDegradedFlag() throws ToolException {
        Map<String, Object> output = toolWith(true).execute(new HashMap<String, Object>(
                Collections.singletonMap("query", "褐色轮纹斑")));
        assertEquals(Boolean.TRUE, output.get("degraded"));
        assertEquals("EMBEDDING_UNAVAILABLE", output.get("degradedReason"));
        assertFalse(((List<?>) output.get("citations")).isEmpty());
    }

    @Test
    void rejectsMissingQuery() {
        assertThrows(ToolException.class, () -> toolWith(false).execute(new HashMap<String, Object>()));
    }

    /** 关键词零命中时必须说明"知识库里没有相关内容"，上层才能直接告诉用户资料库不足。 */
    @Test
    void explainsZeroHitWithNote() throws ToolException {
        Map<String, Object> output = toolWith(false).execute(new HashMap<String, Object>(
                Collections.singletonMap("query", "量子计算机的原理")));

        assertEquals(Boolean.TRUE, output.get("lowScore"));
        String note = String.valueOf(output.get("note"));
        assertTrue(note.contains("零命中"), "应说明是关键词零命中：" + note);
        assertTrue(note.contains("量子计算机"), "应带上查询串便于核对：" + note);
    }

    /** 检索到了但相关性不足时，note 要给出覆盖率，让用户能分辨是问法问题还是资料缺口。 */
    @Test
    void explainsInsufficientCoverageWithNote() throws ToolException {
        Map<String, Object> output = toolWith(false).execute(new HashMap<String, Object>(
                Collections.singletonMap("query", "番茄怎么施肥")));

        assertEquals(Boolean.TRUE, output.get("lowScore"));
        String note = String.valueOf(output.get("note"));
        assertTrue(note.contains("相关性不足"), "应说明相关性不足：" + note);
        assertTrue(note.contains("覆盖率"), "应给出覆盖率数值：" + note);
    }

    /** 有可用证据时不得带"资料库不足"式的说明，否则会误导用户以为库里没有。 */
    @Test
    void doesNotAddNoteWhenEvidenceIsUsable() throws ToolException {
        Map<String, Object> output = toolWith(false).execute(new HashMap<String, Object>(
                Collections.singletonMap("query", "褐色轮纹斑")));

        assertEquals(Boolean.FALSE, output.get("lowScore"));
        assertNull(output.get("note"), "有依据时不应给出缺依据的说明");
    }
}
