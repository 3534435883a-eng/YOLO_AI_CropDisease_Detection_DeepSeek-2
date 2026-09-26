package com.example.Ece.agent.graph;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 图谱服务的测试：重建要幂等、补充要说人话、绝不为了"看起来有图"而输出空话。 */
class KnowledgeGraphServiceTest {

    private KnowledgeGraph sampleGraph() {
        List<KnowledgeGraph.Node> nodes = new ArrayList<KnowledgeGraph.Node>();
        nodes.add(new KnowledgeGraph.Node("DISEASE", "番茄早疫病", null, "原文", null, "v1"));
        nodes.add(new KnowledgeGraph.Node("CONTROL_CATEGORY", "农业防治", null, "原文", null, "v1"));
        nodes.add(new KnowledgeGraph.Node("DISEASE", "葡萄白粉病", null, "原文", null, "v1"));
        nodes.add(new KnowledgeGraph.Node("DISEASE", "葡萄霜霉病", null, "原文", null, "v1"));
        List<KnowledgeGraph.Edge> edges = new ArrayList<KnowledgeGraph.Edge>();
        edges.add(new KnowledgeGraph.Edge("DISEASE", "番茄早疫病", "HAS_CONTROL",
                "CONTROL_CATEGORY", "农业防治", null, "原文", null));
        edges.add(new KnowledgeGraph.Edge("DISEASE", "葡萄白粉病", "CONFUSABLE_WITH",
                "DISEASE", "葡萄霜霉病", null, "科普中国", "https://www.kepuchina.cn/x"));
        edges.add(new KnowledgeGraph.Edge("DISEASE", "缺失节点", "HAS_CONTROL",
                "CONTROL_CATEGORY", "农业防治", null, "原文", null));
        return new KnowledgeGraph("v1", nodes, edges);
    }

    /**
     * 服务的职责是**如实计数**：仓储收下几条就记几条，拒了几条就报几条。
     *
     * <p>不在这里复刻仓储"两端是否存在"的判断——那会让测试变成仓储逻辑的镜像，
     * 而不是验证服务自己的契约。这里让仓储对某一条返回 false，看服务是否如实计入 skipped。</p>
     */
    @Test
    void refreshCountsAcceptedAndSkippedEdgesHonestly() {
        KnowledgeGraphExtractor extractor = mock(KnowledgeGraphExtractor.class);
        when(extractor.extract()).thenReturn(sampleGraph());
        KnowledgeGraphRepository repository = mock(KnowledgeGraphRepository.class);
        Map<String, Long> ids = new LinkedHashMap<String, Long>();
        ids.put("DISEASE|番茄早疫病", Long.valueOf(1L));
        ids.put("CONTROL_CATEGORY|农业防治", Long.valueOf(2L));
        ids.put("DISEASE|葡萄白粉病", Long.valueOf(3L));
        ids.put("DISEASE|葡萄霜霉病", Long.valueOf(4L));
        when(repository.upsertNodes(any())).thenReturn(ids);
        when(repository.upsertEdge(any(), any(KnowledgeGraph.Edge.class), anyString()))
                .thenAnswer(invocation -> {
                    KnowledgeGraph.Edge edge = invocation.getArgument(1);
                    return Boolean.valueOf(!"缺失节点".equals(edge.getHeadName()));
                });

        Map<String, Object> result = new KnowledgeGraphService(extractor, repository).refresh();

        assertEquals(Integer.valueOf(4), result.get("nodes"));
        assertEquals(Integer.valueOf(2), result.get("edges"), "样例图 3 条边，其中 1 条被仓储拒收");
        assertEquals(Integer.valueOf(1), result.get("skippedEdges"));
        verify(repository).deleteOtherVersions("v1");
    }

    @Test
    void edgeWithAMissingEndpointIsCountedAsSkippedRatherThanSilentlyDropped() {
        KnowledgeGraphExtractor extractor = mock(KnowledgeGraphExtractor.class);
        when(extractor.extract()).thenReturn(sampleGraph());
        KnowledgeGraphRepository repository = mock(KnowledgeGraphRepository.class);
        Map<String, Long> ids = new LinkedHashMap<String, Long>();
        when(repository.upsertNodes(any())).thenReturn(ids);
        // 节点表为空时，所有边都缺端点
        when(repository.upsertEdge(any(), any(KnowledgeGraph.Edge.class), anyString())).thenReturn(Boolean.FALSE);

        Map<String, Object> result = new KnowledgeGraphService(extractor, repository).refresh();

        assertEquals(Integer.valueOf(0), result.get("edges"));
        assertEquals(Integer.valueOf(3), result.get("skippedEdges"),
                "两端缺任一端就不造边，但必须计数——不能静默丢弃");
    }

    @Test
    void enrichmentStatesControlCategoriesFromTheOriginalText() {
        KnowledgeGraphRepository repository = mock(KnowledgeGraphRepository.class);
        when(repository.controlCategoriesOf("番茄早疫病"))
                .thenReturn(new ArrayList<String>(Arrays.asList("农业防治", "种子处理")));
        when(repository.edgesByRelation(eq("CONFUSABLE_WITH"), anyInt())).thenReturn(new ArrayList<Map<String, Object>>());
        KnowledgeGraphService service = new KnowledgeGraphService(mock(KnowledgeGraphExtractor.class), repository);

        String text = service.enrichmentFor(new ArrayList<String>(Arrays.asList("番茄早疫病")));

        assertTrue(text != null && text.contains("农业防治、种子处理"));
        assertTrue(text.contains("来自知识库原文明文，非推断"), "必须说明补充的来源性质");
    }

    @Test
    void enrichmentWarnsWhenAConfusablePairIsBothInTheHits() {
        KnowledgeGraphRepository repository = mock(KnowledgeGraphRepository.class);
        when(repository.controlCategoriesOf(anyString())).thenReturn(new ArrayList<String>());
        Map<String, Object> pair = new LinkedHashMap<String, Object>();
        pair.put("head_name", "葡萄白粉病");
        pair.put("tail_name", "葡萄霜霉病");
        pair.put("source_name", "科普中国《葡萄霜霉病和白粉病 防治前要分清》 https://www.kepuchina.cn/x");
        when(repository.edgesByRelation(eq("CONFUSABLE_WITH"), anyInt()))
                .thenReturn(new ArrayList<Map<String, Object>>(Arrays.asList(pair)));
        KnowledgeGraphService service = new KnowledgeGraphService(mock(KnowledgeGraphExtractor.class), repository);

        String text = service.enrichmentFor(new ArrayList<String>(Arrays.asList("葡萄白粉病", "葡萄霜霉病")));

        assertTrue(text != null && text.contains("易混淆"), "两条易混淆病同时命中时应提示鉴别");
        assertTrue(text.contains("科普中国"), "提示必须带出处");
    }

    @Test
    void enrichmentIsNullRatherThanEmptyFillerWhenTheGraphKnowsNothing() {
        KnowledgeGraphRepository repository = mock(KnowledgeGraphRepository.class);
        when(repository.controlCategoriesOf(anyString())).thenReturn(new ArrayList<String>());
        when(repository.edgesByRelation(eq("CONFUSABLE_WITH"), anyInt())).thenReturn(new ArrayList<Map<String, Object>>());
        KnowledgeGraphService service = new KnowledgeGraphService(mock(KnowledgeGraphExtractor.class), repository);

        assertNull(service.enrichmentFor(new ArrayList<String>(Arrays.asList("某个图谱里没有的病"))),
                "图上没有信息时应返回 null，不得输出空话充数");
        assertNull(service.enrichmentFor(new ArrayList<String>()));
        assertNull(service.enrichmentFor(null));
    }

    @Test
    void summaryDisclosesWhatWasDeliberatelyNotModelled() {
        KnowledgeGraphRepository repository = mock(KnowledgeGraphRepository.class);
        when(repository.edgesByRelation(anyString(), anyInt())).thenReturn(new ArrayList<Map<String, Object>>());
        KnowledgeGraphService service = new KnowledgeGraphService(mock(KnowledgeGraphExtractor.class), repository);

        Map<String, Object> summary = service.summary();

        assertTrue(summary.containsKey("nodes"));
        assertTrue(summary.containsKey("edgesByRelation"));
        @SuppressWarnings("unchecked")
        List<String> notModelled = (List<String>) summary.get("notModelled");
        assertFalse(notModelled.isEmpty(), "必须披露刻意未建模的关系");
        String joined = String.join(" ", notModelled);
        assertTrue(joined.contains("病原"), "应说明病原关系为何不做");
        assertTrue(joined.contains("药剂"), "应说明药剂关系为何不做");
        assertTrue(joined.contains("列投影"), "应说明列投影为何不做");
    }

    @Test
    void graphVersionMatchesTheExtractorSoRefreshAndQueryAgree() {
        assertEquals(KnowledgeGraphExtractor.GRAPH_VERSION, KnowledgeGraphService.GRAPH_VERSION);
    }
}
