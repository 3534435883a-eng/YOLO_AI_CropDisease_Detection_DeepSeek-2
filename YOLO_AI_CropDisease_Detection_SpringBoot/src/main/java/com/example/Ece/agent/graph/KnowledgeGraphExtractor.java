package com.example.Ece.agent.graph;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识图谱抽取：从**知识库原文**构造节点与边。
 *
 * <p><b>为什么边才是价值所在</b>：`docs/knowledge-entity-lexicon.md` §四 实测记录过一条被否定的做法——
 * 把 {@code crop_type}/{@code disease_name}/{@code field_type} 这些**现有列**重新投影成节点与边
 * "并不增加任何能力"，因为 `SELECT DISTINCT … WHERE` 就能得到。因此本类只抽出**列里没有的关系**：</p>
 *
 * <ul>
 *   <li>{@code DISEASE —HAS_CONTROL→ CONTROL_CATEGORY}：防治原文里的分节标记
 *       （`(1)农业防治 (2)化学防治 (3)种子处理`…）。原文是一整列 TEXT，**类别结构在其中而非列里**，
 *       且类别名是**字面出现**的，不靠推断——实测 84/100 条含 ≥2 个类别。</li>
 *   <li>{@code SUBTYPE —SUBTYPE_OF→ DISEASE}：原文明确枚举的亚型
 *       （"稻瘟病可分为苗瘟、叶瘟、节瘟、穗颈瘟和谷粒瘟"）。**逐条人工核对过**，
 *       并**驳回了一条模式假阳性**——小麦条锈病的"可分为越夏/秋苗感染/越冬/春季流行"是生活史阶段、不是亚型。</li>
 *   <li>{@code DISEASE ↔ DISEASE} 易混淆：只收**有可引用出处**的对，
 *       目前一条（葡萄白粉病 ↔ 葡萄霜霉病，科普中国页面明确"防治前要分清"）。</li>
 * </ul>
 *
 * <p><b>刻意不做的三类</b>（均已被实测否定，见该文档 §四）：列投影；病害—病原（100 条中 0 条含属种双名）；
 * 病害—药剂（抽取质量问题："1 000倍液"被截成"000倍液"）。宁可图小，不可图假。</p>
 */
@Component
public class KnowledgeGraphExtractor {

    /** 图谱版本。抽取规则或来源变化时应递增——与项目的版本纪律一致。 */
    public static final String GRAPH_VERSION = "kg-2026-09-26";

    /** 库内来源标识（用于节点与边的来源登记）。 */
    public static final String LEGACY_SOURCE_NAME = "cropdisease.disease 表防治原文（E 类项目历史数据）";

    /**
     * 防治措施类别：**字面出现在原文里的分节标记**。
     * 只收这些，不做同义扩展——扩展会引入"原文没写"的关系。
     */
    private static final List<String> CONTROL_CATEGORIES = Arrays.asList(
            "农业防治", "化学防治", "药剂防治", "种子处理", "生物防治", "物理防治", "检疫", "栽培");

    /** 原文明确枚举的亚型：{疾病, 亚型, 原文引文}。逐条人工核对过。 */
    private static final List<String[]> DECLARED_SUBTYPES = Arrays.<String[]>asList(
            new String[]{"稻瘟病", "苗瘟", "稻瘟病可分为苗瘟、叶瘟、节瘟、穗颈瘟和谷粒瘟几种"},
            new String[]{"稻瘟病", "叶瘟", "稻瘟病可分为苗瘟、叶瘟、节瘟、穗颈瘟和谷粒瘟几种"},
            new String[]{"稻瘟病", "节瘟", "稻瘟病可分为苗瘟、叶瘟、节瘟、穗颈瘟和谷粒瘟几种"},
            new String[]{"稻瘟病", "穗颈瘟", "稻瘟病可分为苗瘟、叶瘟、节瘟、穗颈瘟和谷粒瘟几种"},
            new String[]{"稻瘟病", "谷粒瘟", "稻瘟病可分为苗瘟、叶瘟、节瘟、穗颈瘟和谷粒瘟几种"},
            new String[]{"玉米锈病", "普通型锈病", "症状可分为普通型锈病和南方型锈病"},
            new String[]{"玉米锈病", "南方型锈病", "症状可分为普通型锈病和南方型锈病"});

    /** 易混淆对：只收有可引用出处的。 */
    private static final List<String[]> DECLARED_CONFUSABLE = Arrays.<String[]>asList(
            new String[]{"葡萄白粉病", "葡萄霜霉病",
                    "科普中国《葡萄霜霉病和白粉病 防治前要分清》",
                    "https://www.kepuchina.cn/article/articleinfo?ar_id=150183&business_type=100&classify=0"});

    private static final RowMapper<String[]> DISEASE_ROW = new RowMapper<String[]>() {
        public String[] mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new String[]{rs.getString("name"), rs.getString("crop_type"), rs.getString("prevention")};
        }
    };

    private final JdbcTemplate jdbcTemplate;

    public KnowledgeGraphExtractor(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 抽取整张图。同一份知识库必然抽出同一张图（无随机、无外部调用）。 */
    public KnowledgeGraph extract() {
        List<KnowledgeGraph.Node> nodes = new ArrayList<KnowledgeGraph.Node>();
        List<KnowledgeGraph.Edge> edges = new ArrayList<KnowledgeGraph.Edge>();
        Map<String, String> nodeKeys = new LinkedHashMap<String, String>();

        List<String[]> diseases = jdbcTemplate.query(
                "SELECT name, crop_type, prevention FROM disease ORDER BY id", DISEASE_ROW);

        // 1) 疾病与作物节点（作为边的锚点；价值在边，不在这两个节点本身）
        for (String[] disease : diseases) {
            addNode(nodes, nodeKeys, "DISEASE", disease[0], null);
            if (disease[1] != null && !disease[1].trim().isEmpty()) {
                addNode(nodes, nodeKeys, "CROP", disease[1].trim(), null);
            }
        }
        // 2) 防治类别节点 + HAS_CONTROL 边（原文分节标记，字面匹配）
        for (String category : CONTROL_CATEGORIES) {
            addNode(nodes, nodeKeys, "CONTROL_CATEGORY", category, null);
        }
        for (String[] disease : diseases) {
            String prevention = disease[2] == null ? "" : disease[2];
            for (String category : CONTROL_CATEGORIES) {
                if (prevention.contains(category)) {
                    edges.add(new KnowledgeGraph.Edge("DISEASE", disease[0], "HAS_CONTROL",
                            "CONTROL_CATEGORY", category, null, LEGACY_SOURCE_NAME, null));
                }
            }
        }
        // 3) 亚型边（原文枚举，逐条核对）
        for (String[] declared : DECLARED_SUBTYPES) {
            addNode(nodes, nodeKeys, "SUBTYPE", declared[1], null);
            edges.add(new KnowledgeGraph.Edge("SUBTYPE", declared[1], "SUBTYPE_OF",
                    "DISEASE", declared[0], null, "原文枚举：" + declared[2], null));
            edges.add(new KnowledgeGraph.Edge("DISEASE", declared[0], "HAS_SUBTYPE",
                    "SUBTYPE", declared[1], null, "原文枚举：" + declared[2], null));
        }
        // 4) 易混淆边（有可引用出处）
        for (String[] pair : DECLARED_CONFUSABLE) {
            edges.add(new KnowledgeGraph.Edge("DISEASE", pair[0], "CONFUSABLE_WITH",
                    "DISEASE", pair[1], null, pair[2], pair[3]));
        }
        return new KnowledgeGraph(GRAPH_VERSION, nodes, edges);
    }

    private void addNode(List<KnowledgeGraph.Node> nodes, Map<String, String> seen,
                         String type, String name, String alias) {
        if (name == null || name.trim().isEmpty()) {
            return;
        }
        String key = type + "|" + name;
        if (seen.put(key, key) != null) {
            return;
        }
        nodes.add(new KnowledgeGraph.Node(type, name.trim(), alias, LEGACY_SOURCE_NAME, null, GRAPH_VERSION));
    }
}
