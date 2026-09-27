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

    @Test
    void clampsRequestedTopNToSafeBounds() throws ToolException {
        List<KnowledgeChunk> chunks = new java.util.ArrayList<KnowledgeChunk>();
        for (int i = 0; i < 15; i++) {
            chunks.add(new KnowledgeChunk("disease", i + 1L, "番茄", "病害" + i,
                    KnowledgeChunk.FieldType.SYMPTOM, 0, 0, "番茄叶片出现褐色轮纹斑", "hash" + i));
        }
        KnowledgeRetriever retriever = new KnowledgeRetriever(new EmbeddingClient() {
            public double[] embed(String text) {
                return new double[]{1.0, 0.0};
            }
        });
        retriever.rebuild(chunks);
        KnowledgeSearchTool tool = new KnowledgeSearchTool(retriever, new CitationFormatter());
        Map<String, Object> input = new HashMap<String, Object>();
        input.put("query", "褐色轮纹斑");
        input.put("topN", Integer.valueOf(1000));
        Map<String, Object> output = tool.execute(input);

        assertEquals(10, ((List<?>) output.get("citations")).size(),
                "工具不得按模型参数无限放大返回条数");
    }
    /**
     * 工具自述必须覆盖库里**真实存在**的类别。
     *
     * <p>2026-09-27 实测：原描述只写"作物病害知识库…用于回答病害识别、症状判断与防治方案问题"，
     * 于是问「定植后这一周该做哪些农事管理」时，规划步**只调了温室状态工具、从未调用本工具**，
     * 回答"知识库当前缺少该主题的依据"——而库里已有 53 块番茄农事知识。**系统拒答了它答得出的问题。**</p>
     *
     * <p>这条断言把"描述"与"库内容"绑在一起：库加了栽培/水肥/环境/防治/成本这些类别之后，
     * 描述里必须相应出现它们的说法，否则规划步会再次被引开。
     * 与 {@code prescription.draft} 那次是同一类错误的两个方向（那次工具**多报**了能力）。</p>
     */
    @Test
    void descriptionCoversNonDiseaseKnowledgeCategories() {
        String description = new KnowledgeSearchTool(null, null).description();
        for (String keyword : new String[]{"栽培", "水肥", "环境", "防治"}) {
            assertTrue(description.contains(keyword),
                    "工具自述漏了库里已有的类别「" + keyword + "」，规划步会因此不检索：" + description);
        }
        assertTrue(description.contains("农事管理"),
                "工具自述未点明农事管理用途——这正是实测被拒答的那类问题：" + description);
    }
}
