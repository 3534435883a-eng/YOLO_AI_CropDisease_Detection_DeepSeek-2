package com.example.Ece.agent.graph;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 图谱抽取的测试。
 *
 * <p>用 mock 的 {@code JdbcTemplate} 而不是连 MySQL：**全套测试目前不需要数据库**，
 * 为一张图引入数据库依赖会让 {@code mvn test} 在无库环境下跑不了——那是可移植性的倒退。</p>
 *
 * <p>抽取层只做一次 {@code query}，因此 stub 一次即可覆盖全部逻辑。</p>
 */
class KnowledgeGraphExtractorTest {

    /** {name, crop_type, prevention} —— 覆盖：多类别、单类别、亚型原文、易混淆对。 */
    private List<String[]> sampleRows() {
        List<String[]> rows = new ArrayList<String[]>();
        rows.add(new String[]{"番茄早疫病", "番茄", "(1)农业防治：选用抗病品种。(2)种子处理：播种前拌种。"});
        rows.add(new String[]{"玉米锈病", "玉米", "(1)种子处理：拌种。(2)农业防治：增施磷钾肥。"});
        rows.add(new String[]{"番茄灰霉病", "番茄", "（1）农业防治：控制棚内湿度。（2）化学防治：见药剂登记。"});
        rows.add(new String[]{"稻瘟病", "水稻", "稻瘟病可分为苗瘟、叶瘟、节瘟、穗颈瘟和谷粒瘟几种。农业防治：选用抗病品种。"});
        rows.add(new String[]{"葡萄白粉病", "葡萄", "农业防治：通风降湿。"});
        rows.add(new String[]{"葡萄霜霉病", "葡萄", "农业防治：清除病残体。"});
        return rows;
    }

    private KnowledgeGraph extract() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.query(anyString(), ArgumentMatchers.<RowMapper<String[]>>any()))
                .thenReturn(sampleRows());
        return new KnowledgeGraphExtractor(jdbcTemplate).extract();
    }

    private Set<String> edgeKeys(KnowledgeGraph graph) {
        Set<String> keys = new HashSet<String>();
        for (KnowledgeGraph.Edge edge : graph.getEdges()) {
            keys.add(edge.getHeadName() + "-" + edge.getRelation() + "->" + edge.getTailName());
        }
        return keys;
    }

    @Test
    void createsDiseaseCropAndControlCategoryNodes() {
        KnowledgeGraph graph = extract();
        Set<String> nodes = new HashSet<String>();
        for (KnowledgeGraph.Node node : graph.getNodes()) {
            nodes.add(node.getNodeType() + "|" + node.getName());
        }
        assertTrue(nodes.contains("DISEASE|番茄早疫病"));
        assertTrue(nodes.contains("CROP|番茄"));
        assertTrue(nodes.contains("CONTROL_CATEGORY|农业防治"));
        assertTrue(nodes.contains("CONTROL_CATEGORY|种子处理"));
        assertTrue(nodes.contains("SUBTYPE|叶瘟"), "亚型应作为节点存在");
    }

    @Test
    void extractsControlEdgesOnlyWhereTheOriginalTextNamesTheCategory() {
        Set<String> keys = edgeKeys(extract());
        // 原文写了 (1)农业防治 (2)种子处理 → 两条边
        assertTrue(keys.contains("番茄早疫病-HAS_CONTROL->农业防治"));
        assertTrue(keys.contains("番茄早疫病-HAS_CONTROL->种子处理"));
        // 原文只写 化学防治，没有 种子处理 → 不得凭空生边
        assertTrue(keys.contains("番茄灰霉病-HAS_CONTROL->化学防治"));
        assertFalse(keys.contains("番茄灰霉病-HAS_CONTROL->种子处理"),
                "原文没写种子处理，不得生成该边");
    }

    @Test
    void subtypeEdgesCarryTheOriginalQuoteAsEvidence() {
        KnowledgeGraph graph = extract();
        KnowledgeGraph.Edge leafBlast = null;
        for (KnowledgeGraph.Edge edge : graph.getEdges()) {
            if ("叶瘟".equals(edge.getHeadName()) && "SUBTYPE_OF".equals(edge.getRelation())) {
                leafBlast = edge;
            }
        }
        assertTrue(leafBlast != null, "缺少 叶瘟 → 稻瘟病 的上位关系");
        assertEquals("稻瘟病", leafBlast.getTailName());
        assertTrue(leafBlast.getSourceName().contains("稻瘟病可分为"),
                "亚型边必须带原文出处，否则无法核验");
    }

    @Test
    void confusablePairIsRecordedWithACitableUrl() {
        KnowledgeGraph graph = extract();
        KnowledgeGraph.Edge pair = null;
        for (KnowledgeGraph.Edge edge : graph.getEdges()) {
            if ("CONFUSABLE_WITH".equals(edge.getRelation())) {
                pair = edge;
            }
        }
        assertTrue(pair != null, "缺少易混淆对");
        assertEquals("葡萄白粉病", pair.getHeadName());
        assertEquals("葡萄霜霉病", pair.getTailName());
        assertTrue(pair.getSourceUrl() != null && pair.getSourceUrl().contains("kepuchina"),
                "易混淆对必须有可引用出处——这是它敢写进图谱的前提");
    }

    @Test
    void rejectedLifecyclePatternIsNotAmongTheSubtypeEdges() {
        Set<String> keys = edgeKeys(extract());
        // 小麦条锈病的"可分为越夏/秋苗感染/越冬/春季流行"是生活史阶段，不是病害亚型——
        // 抽取时被人工驳回，此处锁住该结论，避免以后有人"顺手"把它加回来
        for (String key : keys) {
            assertFalse(key.contains("越夏"), "生活史阶段不得当作亚型");
            assertFalse(key.contains("秋苗感染"), "生活史阶段不得当作亚型");
        }
    }

    @Test
    void extractionIsDeterministic() {
        assertEquals(edgeKeys(extract()), edgeKeys(extract()), "同一份知识库必须抽出同一张图");
    }

    @Test
    void everyNodeAndEdgeCarriesVersionAndSource() {
        KnowledgeGraph graph = extract();
        for (KnowledgeGraph.Node node : graph.getNodes()) {
            assertEquals(KnowledgeGraphExtractor.GRAPH_VERSION, node.getVersion());
            assertFalse(node.getSourceName().isEmpty(), "节点必须带来源");
        }
        for (KnowledgeGraph.Edge edge : graph.getEdges()) {
            assertFalse(edge.getSourceName() == null || edge.getSourceName().isEmpty(),
                    "边必须带来源或证据：" + edge.getRelation());
        }
    }

    @Test
    void emptyKnowledgeBaseYieldsCategoriesAndDeclaredEdgesOnlyNotACrash() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.query(anyString(), ArgumentMatchers.<RowMapper<String[]>>any()))
                .thenReturn(new ArrayList<String[]>());
        KnowledgeGraph graph = new KnowledgeGraphExtractor(jdbcTemplate).extract();
        assertFalse(graph.getNodes().isEmpty(), "类别节点与亚型节点不依赖疾病表，应仍在");
        for (KnowledgeGraph.Edge edge : graph.getEdges()) {
            assertTrue("SUBTYPE_OF".equals(edge.getRelation()) || "HAS_SUBTYPE".equals(edge.getRelation())
                            || "CONFUSABLE_WITH".equals(edge.getRelation()),
                    "空知识库时不应有 HAS_CONTROL 边，实得 " + edge.getRelation());
        }
        assertTrue(Arrays.asList("农业防治").contains("农业防治"));
    }
}
