package com.example.Ece.agent.graph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 知识图谱的内存表示：节点 + 边 + 版本。
 *
 * <p>边用**两端节点的类型与名称**表达（而不是数据库里的 id），因为抽取层只认名称，
 * 由 {@code KnowledgeGraphRepository} 负责把名称解析成 {@code agent_knowledge_node.id}。
 * 这样抽取逻辑不依赖持久化的主键，重跑与换库都不受影响。</p>
 */
public class KnowledgeGraph {

    private final String version;
    private final List<Node> nodes;
    private final List<Edge> edges;

    public KnowledgeGraph(String version, List<Node> nodes, List<Edge> edges) {
        this.version = version;
        this.nodes = nodes == null ? new ArrayList<Node>() : nodes;
        this.edges = edges == null ? new ArrayList<Edge>() : edges;
    }

    public String getVersion() {
        return version;
    }

    public List<Node> getNodes() {
        return Collections.unmodifiableList(nodes);
    }

    public List<Edge> getEdges() {
        return Collections.unmodifiableList(edges);
    }

    /** 某个节点类型下的全部名称，供检索侧做查询扩展。 */
    public List<String> namesOfType(String nodeType) {
        List<String> names = new ArrayList<String>();
        for (Node node : nodes) {
            if (node.getNodeType().equals(nodeType)) {
                names.add(node.getName());
            }
        }
        return names;
    }

    /** 节点：类型 + 名称唯一。 */
    public static final class Node {
        private final String nodeType;
        private final String name;
        private final String alias;
        private final String sourceName;
        private final String sourceUrl;
        private final String version;

        public Node(String nodeType, String name, String alias, String sourceName,
                    String sourceUrl, String version) {
            this.nodeType = nodeType;
            this.name = name;
            this.alias = alias;
            this.sourceName = sourceName;
            this.sourceUrl = sourceUrl;
            this.version = version;
        }

        public String getNodeType() {
            return nodeType;
        }

        public String getName() {
            return name;
        }

        public String getAlias() {
            return alias;
        }

        public String getSourceName() {
            return sourceName;
        }

        public String getSourceUrl() {
            return sourceUrl;
        }

        public String getVersion() {
            return version;
        }
    }

    /** 边：头节点（类型+名称）— 关系 → 尾节点（类型+名称），带来源与证据。 */
    public static final class Edge {
        private final String headType;
        private final String headName;
        private final String relation;
        private final String tailType;
        private final String tailName;
        private final Double weight;
        private final String sourceName;
        private final String sourceUrl;

        public Edge(String headType, String headName, String relation, String tailType, String tailName,
                    Double weight, String sourceName, String sourceUrl) {
            this.headType = headType;
            this.headName = headName;
            this.relation = relation;
            this.tailType = tailType;
            this.tailName = tailName;
            this.weight = weight;
            this.sourceName = sourceName;
            this.sourceUrl = sourceUrl;
        }

        public String getHeadType() {
            return headType;
        }

        public String getHeadName() {
            return headName;
        }

        public String getRelation() {
            return relation;
        }

        public String getTailType() {
            return tailType;
        }

        public String getTailName() {
            return tailName;
        }

        public Double getWeight() {
            return weight;
        }

        public String getSourceName() {
            return sourceName;
        }

        public String getSourceUrl() {
            return sourceUrl;
        }
    }
}
