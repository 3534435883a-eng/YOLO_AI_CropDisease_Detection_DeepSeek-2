package com.example.Ece.agent.graph;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识图谱服务：重建图谱、查询邻接、给检索侧提供结构化补充。
 *
 * <p>图谱的两张表在本项目里存在已久（含 9 个作物别名节点），但**零 Java 引用、0 条边**——
 * 又一次"建了没接线"。本服务把它接上。</p>
 */
@Service
public class KnowledgeGraphService {

    /** 图谱版本，随抽取规则或来源变化递增。 */
    public static final String GRAPH_VERSION = KnowledgeGraphExtractor.GRAPH_VERSION;

    private final KnowledgeGraphExtractor extractor;
    private final KnowledgeGraphRepository repository;

    public KnowledgeGraphService(KnowledgeGraphExtractor extractor, KnowledgeGraphRepository repository) {
        this.extractor = extractor;
        this.repository = repository;
    }

    /**
     * 从知识库原文重建图谱。
     *
     * <p>幂等：节点与边都按唯一键 upsert；重建后清掉非当前版本的数据。
     * 抽取无随机、无外部调用，因此同一份知识库必然得到同一张图。</p>
     *
     * @return 写入统计
     */
    public Map<String, Object> refresh() {
        KnowledgeGraph graph = extractor.extract();
        Map<String, Long> nodeIds = repository.upsertNodes(graph.getNodes());
        int written = 0;
        int skipped = 0;
        for (KnowledgeGraph.Edge edge : graph.getEdges()) {
            if (repository.upsertEdge(nodeIds, edge, graph.getVersion())) {
                written++;
            } else {
                // 两端缺任一端就不造边——宁可少一条，也不造一条指向不存在节点的边。
                skipped++;
            }
        }
        repository.deleteOtherVersions(graph.getVersion());
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("version", graph.getVersion());
        result.put("nodes", Integer.valueOf(nodeIds.size()));
        result.put("edges", Integer.valueOf(written));
        result.put("skippedEdges", Integer.valueOf(skipped));
        return result;
    }

    /** 某节点的邻接边（双向）。 */
    public List<Map<String, Object>> neighbours(String nodeType, String name, int limit) {
        return repository.neighbours(nodeType, name, limit);
    }

    /** 疾病在图上登记的防治类别，如「农业防治、种子处理」。无登记返回空列表。 */
    public List<String> controlCategoriesOf(String diseaseName) {
        if (diseaseName == null || diseaseName.trim().isEmpty()) {
            return new ArrayList<String>();
        }
        return repository.controlCategoriesOf(diseaseName.trim());
    }

    /**
     * 检索侧用的结构化补充：把图上关于这些疾病的**列里没有的信息**拼成一句提示。
     *
     * <p>只补两类：防治类别（原文分节标记）与易混淆对（有可引用出处）。
     * 拼不出内容时返回 null——**不为了"看起来有图"而输出空话**。</p>
     */
    public String enrichmentFor(List<String> diseaseNames) {
        if (diseaseNames == null || diseaseNames.isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<String>();
        for (String name : diseaseNames) {
            if (name == null || name.trim().isEmpty()) {
                continue;
            }
            List<String> categories = controlCategoriesOf(name);
            if (!categories.isEmpty()) {
                parts.add("「" + name + "」原文登记的防治类别：" + String.join("、", categories));
            }
        }
        String confusable = confusableHint(diseaseNames);
        if (confusable != null) {
            parts.add(confusable);
        }
        if (parts.isEmpty()) {
            return null;
        }
        return "图谱补充（来自知识库原文明文，非推断）：" + String.join("；", parts) + "。";
    }

    /** 命中集合里若出现有出处的易混淆对，提示需鉴别。 */
    private String confusableHint(List<String> diseaseNames) {
        List<Map<String, Object>> pairs = repository.edgesByRelation("CONFUSABLE_WITH", 50);
        for (Map<String, Object> pair : pairs) {
            String head = String.valueOf(pair.get("head_name"));
            String tail = String.valueOf(pair.get("tail_name"));
            if (diseaseNames.contains(head) && diseaseNames.contains(tail)) {
                return "注意：「" + head + "」与「" + tail + "」是有出处的易混淆对（"
                        + pair.get("source_name") + "），需按特征鉴别后再下结论";
            }
        }
        return null;
    }

    /** 图谱概况：节点与边按类型/关系的计数。 */
    public Map<String, Object> summary() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("version", GRAPH_VERSION);
        result.put("nodes", Integer.valueOf(repository.countNodes()));
        result.put("edges", Integer.valueOf(repository.countEdges()));
        Map<String, Object> byRelation = new LinkedHashMap<String, Object>();
        for (String relation : new String[]{"HAS_CONTROL", "SUBTYPE_OF", "HAS_SUBTYPE", "CONFUSABLE_WITH"}) {
            byRelation.put(relation, Integer.valueOf(repository.edgesByRelation(relation, 100000).size()));
        }
        result.put("edgesByRelation", byRelation);
        result.put("notModelled", NOT_MODELLED);
        return result;
    }

    /**
     * 刻意**没有**建模的关系，以及原因。
     *
     * <p>列表本身就随接口返回：图谱"有什么"要被看见，"为什么没有"同样要被看见——
     * 否则看图的人会以为这些关系不存在，而不是"被测量否定过"。</p>
     */
    public static final List<String> NOT_MODELLED = java.util.Collections.unmodifiableList(java.util.Arrays.asList(
            "病害—病原：实测 100 条中 0 条含属种双名，无法抽取（docs/knowledge-entity-lexicon.md §四.2）",
            "病害—药剂：抽取质量不合格（\"1 000倍液\"被截成\"000倍液\"、有效成分名会吞前文），"
                    + "用错误抽取生成知识风险高于收益，故不做（§四.3）",
            "列投影（作物—病害—字段类型）：现有列 SELECT 即可得到，重新投影不增加能力，故不做（§四.1）",
            "单字别名：会在任意含该字的文本中误匹配，故不收录（§四.4）"));
}
