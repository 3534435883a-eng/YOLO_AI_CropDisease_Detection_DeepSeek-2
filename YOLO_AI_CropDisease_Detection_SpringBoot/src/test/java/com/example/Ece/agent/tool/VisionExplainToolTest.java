package com.example.Ece.agent.tool;

import com.example.Ece.agent.rag.CitationFormatter;
import com.example.Ece.agent.rag.EmbeddingClient;
import com.example.Ece.agent.rag.KnowledgeChunk;
import com.example.Ece.agent.rag.KnowledgeEntityLexicon;
import com.example.Ece.agent.rag.KnowledgeRetriever;
import com.example.Ece.agent.repository.JdbcVisionClassMapRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VisionExplainToolTest {

    /** 与生产表结构一致的行：番茄早疫病已核验映射、潜叶虫无对应条目、"早疫病"在番茄与马铃薯都存在（歧义）。 */
    private List<Map<String, Object>> rows() {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        rows.add(row("tomato", "番茄", "Early_Blight(早疫病)", "Early_Blight", "早疫病", "番茄早疫病", "V3",
                "名称包含：番茄早疫病 ⊃ 早疫病"));
        rows.add(row("tomato", "番茄", "Leaf_Miner(潜叶虫)", "Leaf_Miner", "潜叶虫", null, "NONE",
                "未找到可核对的依据，暂不映射"));
        rows.add(row("potato", "马铃薯", "Early_Blight(早疫病)", "Early_Blight", "早疫病", "马铃薯早疫病", "V3",
                "名称包含：马铃薯早疫病 ⊃ 早疫病"));
        rows.add(row("tomato", "番茄", "Healthy(健康)", "Healthy", "健康", null, "HEALTHY", "健康类别"));
        Map<String, Object> external = row("tomato", "番茄", "YLCV(黄化卷叶病毒)", "YLCV", "黄化卷叶病毒",
                "番茄黄化曲叶病", "EXTERNAL", "UC IPM 核验的黄化曲叶病类群");
        external.put("sourceUrl", "https://ipm.ucanr.edu/agriculture/tomato/tomato-yellow-leaf-curl/");
        rows.add(external);
        return rows;
    }

    private Map<String, Object> row(String model, String crop, String classLabel, String en, String zh,
                                    String disease, String rule, String evidence) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("modelCode", model);
        row.put("cropType", crop);
        row.put("classLabel", classLabel);
        row.put("labelEn", en);
        row.put("labelZh", zh);
        row.put("kbDiseaseName", disease);
        row.put("matchRule", rule);
        row.put("evidence", evidence);
        row.put("sourceUrl", null);
        row.put("healthy", Boolean.valueOf("HEALTHY".equals(rule)));
        row.put("explainable", Boolean.valueOf(disease != null));
        return row;
    }

    private VisionExplainTool tool() {
        JdbcVisionClassMapRepository repository = mock(JdbcVisionClassMapRepository.class);
        when(repository.findAll()).thenReturn(rows());
        KnowledgeRetriever retriever = new KnowledgeRetriever(new EmbeddingClient() {
            public double[] embed(String text) {
                return new double[]{1.0, 0.0};
            }
        });
        retriever.rebuild(Arrays.asList(
                new KnowledgeChunk("disease", 85L, "番茄", "番茄早疫病", KnowledgeChunk.FieldType.SYMPTOM,
                        0, 0, "作物：番茄；病害：番茄早疫病；字段：症状。病初现水渍状暗褐色病斑，扩大后近圆形，有同心轮纹", "h85"),
                new KnowledgeChunk("disease", 44L, "马铃薯", "马铃薯早疫病", KnowledgeChunk.FieldType.SYMPTOM,
                        0, 0, "作物：马铃薯；病害：马铃薯早疫病；字段：症状。叶片出现褐黑色小斑点，逐渐形成同心轮纹", "h44"),
                new KnowledgeChunk("curated_tomato", 2L, "番茄", "番茄黄化曲叶病", KnowledgeChunk.FieldType.SYMPTOM,
                        0, 0, "作物：番茄；病害：番茄黄化曲叶病；字段：症状。叶片变小并向上卷曲", "hylcv")));
        return new VisionExplainTool(repository, retriever, new CitationFormatter(), new KnowledgeEntityLexicon());
    }

    private Map<String, Object> input(String label, String crop) {
        Map<String, Object> input = new HashMap<String, Object>();
        input.put("classLabel", label);
        if (crop != null) {
            input.put("crop", crop);
        }
        return input;
    }

    @Test
    void exposesMetadata() {
        VisionExplainTool tool = tool();
        assertEquals("vision.explain", tool.name());
        assertEquals(ToolPermission.READ_ONLY, tool.permission());
        assertTrue(tool.inputSchemaJson().contains("classLabel"), "输入必须包含类别标签");
    }

    @Test
    void explainsMappedClassWithKnowledgeEvidence() throws ToolException {
        Map<String, Object> output = tool().execute(input("Early_Blight(早疫病)", "番茄"));

        assertFalse(((List<?>) output.get("citations")).isEmpty(), "已核验映射必须能取到知识库证据");
        assertEquals(Boolean.FALSE, output.get("lowScore"));
        String note = String.valueOf(output.get("note"));
        assertTrue(note.contains("番茄早疫病"), "说明里应写明对应知识库条目：" + note);
        assertTrue(note.contains("V3"), "说明里应写明映射依据：" + note);
        assertTrue(note.contains("项目内部视觉类别映射表"), "无外部链接的映射应标明内部出处：" + note);
        assertNotNull(output.get("mapping"));
    }

    @Test
    void includesExternalMappingSourceInModelNote() throws ToolException {
        Map<String, Object> output = tool().execute(input("YLCV(黄化卷叶病毒)", "番茄"));

        assertFalse(((List<?>) output.get("citations")).isEmpty());
        assertTrue(String.valueOf(output.get("note")).contains(
                "https://ipm.ucanr.edu/agriculture/tomato/tomato-yellow-leaf-curl/"));
    }

    /** 用别名传作物也要能消歧（复用别名词典归一化）。 */
    @Test
    void acceptsCropAliasWhenDisambiguating() throws ToolException {
        Map<String, Object> output = tool().execute(input("早疫病", "土豆"));
        assertEquals(Boolean.FALSE, output.get("lowScore"));
        assertTrue(String.valueOf(output.get("note")).contains("马铃薯早疫病"));
    }

    @Test
    void doesNotFallBackToAnotherCropWhenCropDoesNotMatch() throws ToolException {
        Map<String, Object> output = tool().execute(input("早疫病", "水稻"));

        assertEquals(Boolean.TRUE, output.get("lowScore"));
        assertTrue(((List<?>) output.get("citations")).isEmpty());
        assertEquals(Boolean.TRUE, output.get("terminal"));
        assertTrue(String.valueOf(output.get("terminalAnswer")).contains("资料库不足"));
        assertTrue(String.valueOf(output.get("terminalAnswer")).contains("水稻"));
        assertFalse(String.valueOf(output.get("terminalAnswer")).contains("不在已登记"));
    }

    /**
     * 没有可核对映射的类别必须明确说"资料库不足"，并给出终止信号。
     * 终止是为了阻止模型继续检索无关内容硬答（实测问"潜叶虫"时检索到 7 条番茄病害）。
     */
    @Test
    void refusesToGuessForUnmappedClass() throws ToolException {
        Map<String, Object> output = tool().execute(input("潜叶虫", "番茄"));

        assertEquals(Boolean.TRUE, output.get("lowScore"));
        assertTrue(((List<?>) output.get("citations")).isEmpty(), "无映射时不得产生任何引用");
        String note = String.valueOf(output.get("note"));
        assertTrue(note.contains("没有可核对的对应条目"), "应明确说明缺依据：" + note);
        assertEquals(Boolean.TRUE, output.get("terminal"), "无对应条目必须终止本轮，不再检索");
        assertEquals("KNOWLEDGE_INSUFFICIENT", output.get("terminalReason"));
        String answer = String.valueOf(output.get("terminalAnswer"));
        assertTrue(answer.contains("资料库不足"), "应直接说明资料库不足：" + answer);
        assertTrue(answer.contains("潜叶虫"), "应写明是哪个类别：" + answer);
    }

    /** 同名类别跨作物且未给作物时必须要求补充，而不是随便选一个（同样是终止本轮）。 */
    @Test
    void asksForCropWhenClassIsAmbiguous() throws ToolException {
        Map<String, Object> output = tool().execute(input("早疫病", null));

        assertEquals(Boolean.TRUE, output.get("lowScore"));
        String note = String.valueOf(output.get("note"));
        assertTrue(note.contains("未指定作物"), "应提示补充作物：" + note);
        List<?> candidates = (List<?>) output.get("candidates");
        assertNotNull(candidates);
        assertTrue(candidates.contains("番茄早疫病") && candidates.contains("马铃薯早疫病"),
                "候选应列出两个作物上的条目：" + candidates);
        assertEquals(Boolean.TRUE, output.get("terminal"));
        assertEquals("NEED_CROP", output.get("terminalReason"));
        assertTrue(String.valueOf(output.get("terminalAnswer")).contains("请说明作物"));
    }

    @Test
    void reportsUnknownLabelInsteadOfMatchingLoosely() throws ToolException {
        Map<String, Object> output = tool().execute(input("不存在的类别", "番茄"));
        assertEquals(Boolean.TRUE, output.get("lowScore"));
        assertTrue(String.valueOf(output.get("note")).contains("未在视觉类别映射表中找到"));
        assertEquals(Boolean.TRUE, output.get("terminal"), "未知类别也应终止本轮");
        assertEquals("KNOWLEDGE_INSUFFICIENT", output.get("terminalReason"));
        assertTrue(String.valueOf(output.get("terminalAnswer")).contains("资料库不足"));
    }

    /** 已核验映射的正常路径不得带终止信号，否则会误伤正常问答。 */
    @Test
    void doesNotSignalTerminalForMappedClass() throws ToolException {
        Map<String, Object> output = tool().execute(input("Early_Blight(早疫病)", "番茄"));
        assertFalse(Boolean.TRUE.equals(output.get("terminal")), "正常映射不应终止本轮");
        assertEquals(Boolean.FALSE, output.get("lowScore"));
    }

    @Test
    void rejectsMissingClassLabel() {
        assertThrows(ToolException.class, () -> tool().execute(new HashMap<String, Object>()));
    }
}
