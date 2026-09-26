package com.example.Ece.agent.graph;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识图谱的持久化读写，落在**已存在**的 {@code agent_knowledge_node} / {@code agent_knowledge_edge} 上。
 *
 * <p>这两张表由 {@code V20260922_01__agent_knowledge_rag.sql} 建好、且 {@code V20260925_01} 已写入 9 个
 * 作物别名节点——但**此前零 Java 引用、0 条边**。本类把它们接上，不新建表、不改既有表
 * （项目约定迁移是 create-only）。</p>
 *
 * <p><b>边没有 source_url 列</b>，而"易混淆"这类关系必须能给出处。因此出处的 URL 折进
 * {@code source_name}（512 字符足够），格式为「来源名 + 空格 + URL」。
 * 与 {@code docs/knowledge-entity-lexicon.md} §五 记录的教训同源：不为了好看去改既有表结构。</p>
 */
@Repository
public class KnowledgeGraphRepository {

    private static final String UPSERT_NODE_SQL = "INSERT INTO agent_knowledge_node "
            + "(node_type, name, alias, source_name, source_url, version, created_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?) "
            + "ON DUPLICATE KEY UPDATE alias = VALUES(alias), source_name = VALUES(source_name), "
            + "source_url = VALUES(source_url)";

    private static final String UPSERT_EDGE_SQL = "INSERT INTO agent_knowledge_edge "
            + "(head_id, relation, tail_id, weight, source_name, version, created_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?) "
            + "ON DUPLICATE KEY UPDATE weight = VALUES(weight), source_name = VALUES(source_name)";

    private final JdbcTemplate jdbcTemplate;

    public KnowledgeGraphRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 写入节点并返回「类型|名称 → id」映射，供解析边的两端。 */
    public Map<String, Long> upsertNodes(List<KnowledgeGraph.Node> nodes) {
        Map<String, Long> ids = new LinkedHashMap<String, Long>();
        Timestamp now = new Timestamp(System.currentTimeMillis());
        for (KnowledgeGraph.Node node : nodes) {
            jdbcTemplate.update(UPSERT_NODE_SQL, node.getNodeType(), node.getName(), node.getAlias(),
                    node.getSourceName(), node.getSourceUrl(), node.getVersion(), now);
            Long id = jdbcTemplate.queryForObject(
                    "SELECT id FROM agent_knowledge_node WHERE node_type = ? AND name = ? AND version = ?",
                    Long.class, node.getNodeType(), node.getName(), node.getVersion());
            ids.put(node.getNodeType() + "|" + node.getName(), id);
        }
        return ids;
    }

    /**
     * 写入边。两端节点的 id 由调用方从 {@link #upsertNodes} 的返回值里取。
     *
     * @return true 表示写入（含更新）；两端节点缺任一端时返回 false，不静默造边
     */
    public boolean upsertEdge(Map<String, Long> nodeIds, KnowledgeGraph.Edge edge, String version) {
        Long headId = nodeIds.get(edge.getHeadType() + "|" + edge.getHeadName());
        Long tailId = nodeIds.get(edge.getTailType() + "|" + edge.getTailName());
        if (headId == null || tailId == null) {
            return false;
        }
        jdbcTemplate.update(UPSERT_EDGE_SQL, headId, edge.getRelation(), tailId, edge.getWeight(),
                sourceWithUrl(edge), version, new Timestamp(System.currentTimeMillis()));
        return true;
    }

    /** 边的出处：来源名 + URL 折进同一列（该表没有 source_url 列）。 */
    private String sourceWithUrl(KnowledgeGraph.Edge edge) {
        String name = edge.getSourceName() == null ? "来源未登记" : edge.getSourceName();
        String url = edge.getSourceUrl();
        if (url == null || url.trim().isEmpty()) {
            return name;
        }
        String combined = name + " " + url.trim();
        return combined.length() <= 512 ? combined : combined.substring(0, 512);
    }

    /** 查某节点的邻接边（双向），带两端名称与出处。 */
    public List<Map<String, Object>> neighbours(String nodeType, String name, int limit) {
        int effectiveLimit = limit <= 0 ? 50 : limit;
        String sql = "SELECT h.node_type AS head_type, h.name AS head_name, e.relation AS relation, "
                + "t.node_type AS tail_type, t.name AS tail_name, e.source_name AS source_name, "
                + "e.weight AS weight FROM agent_knowledge_edge e "
                + "JOIN agent_knowledge_node h ON h.id = e.head_id "
                + "JOIN agent_knowledge_node t ON t.id = e.tail_id "
                + "WHERE (h.node_type = ? AND h.name = ?) OR (t.node_type = ? AND t.name = ?) "
                + "ORDER BY e.relation, t.name LIMIT ?";
        return jdbcTemplate.queryForList(sql, nodeType, name, nodeType, name, Integer.valueOf(effectiveLimit));
    }

    /** 按关系查边，供检索侧做鉴别提示。 */
    public List<Map<String, Object>> edgesByRelation(String relation, int limit) {
        int effectiveLimit = limit <= 0 ? 200 : limit;
        String sql = "SELECT h.name AS head_name, e.relation AS relation, t.name AS tail_name, "
                + "e.source_name AS source_name FROM agent_knowledge_edge e "
                + "JOIN agent_knowledge_node h ON h.id = e.head_id "
                + "JOIN agent_knowledge_node t ON t.id = e.tail_id "
                + "WHERE e.relation = ? ORDER BY h.name LIMIT ?";
        return jdbcTemplate.queryForList(sql, relation, Integer.valueOf(effectiveLimit));
    }

    /** 某条疾病在图上登记的防治类别（去重、按固定顺序）。 */
    public List<String> controlCategoriesOf(String diseaseName) {
        String sql = "SELECT t.name AS category FROM agent_knowledge_edge e "
                + "JOIN agent_knowledge_node h ON h.id = e.head_id "
                + "JOIN agent_knowledge_node t ON t.id = e.tail_id "
                + "WHERE e.relation = 'HAS_CONTROL' AND h.name = ? ORDER BY t.id";
        List<String> categories = new ArrayList<String>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(sql, diseaseName)) {
            Object value = row.get("category");
            if (value != null) {
                categories.add(String.valueOf(value));
            }
        }
        return categories;
    }

    public int countNodes() {
        Integer total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM agent_knowledge_node", Integer.class);
        return total == null ? 0 : total.intValue();
    }

    public int countEdges() {
        Integer total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM agent_knowledge_edge", Integer.class);
        return total == null ? 0 : total.intValue();
    }

    /** 清掉非当前版本的图数据，避免历史版本混在查询结果里。 */
    public void deleteOtherVersions(String keepVersion) {
        jdbcTemplate.update("DELETE FROM agent_knowledge_edge WHERE version <> ?", keepVersion);
        jdbcTemplate.update("DELETE FROM agent_knowledge_node WHERE version <> ?", keepVersion);
    }
}
