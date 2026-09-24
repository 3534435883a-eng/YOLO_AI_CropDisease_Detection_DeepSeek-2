package com.example.Ece.agent.rag;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把检索命中转成可定位的引用（[n] -> 病名/字段/片段号）。
 * 多次工具调用会用 {@link #renumber(List)} 统一重编号，保证全局编号唯一且连续。
 */
@Component
public class CitationFormatter {

    private static final int SNIPPET_LIMIT = 160;

    public List<Map<String, Object>> toCitations(List<ScoredChunk> items) {
        List<Map<String, Object>> citations = new ArrayList<Map<String, Object>>();
        if (items == null) {
            return citations;
        }
        int index = 0;
        for (ScoredChunk item : items) {
            if (item == null || item.getChunk() == null) {
                continue;
            }
            index++;
            KnowledgeChunk chunk = item.getChunk();
            Map<String, Object> citation = new LinkedHashMap<String, Object>();
            citation.put("index", Integer.valueOf(index));
            citation.put("diseaseName", chunk.getDiseaseName());
            citation.put("cropType", chunk.getCropType());
            citation.put("fieldType", chunk.getFieldType() == null ? null : chunk.getFieldType().name());
            citation.put("chunkNo", Integer.valueOf(chunk.getChunkNo()));
            citation.put("startOffset", Integer.valueOf(chunk.getStartOffset()));
            citation.put("score", Double.valueOf(item.getScore()));
            citation.put("sourceTable", chunk.getSourceTable());
            citation.put("sourceId", Long.valueOf(chunk.getSourceId()));
            citation.put("sourceCode", chunk.getSourceCode());
            citation.put("sourceName", chunk.getSourceName() == null ? "来源未登记" : chunk.getSourceName());
            citation.put("sourceType", chunk.getSourceType());
            citation.put("sourceUrl", chunk.getSourceUrl());
            citation.put("sourceVersion", chunk.getSourceVersion());
            citation.put("snippet", snippet(chunk.getContent()));
            citation.put("label", buildLabel(index, chunk.getDiseaseName(),
                    chunk.getFieldType() == null ? null : chunk.getFieldType().name(), chunk.getChunkNo()));
            citations.add(citation);
        }
        return citations;
    }

    public String toPromptBlock(List<ScoredChunk> items) {
        return toEvidenceBlock(toCitations(items));
    }

    /**
     * 合并两批引用：按知识块唯一键（来源表|来源ID|字段|片段号）去重后全局重编号。
     * 同一片段被多次检索命中时只保留一次，避免回答里的 [n] 重复指向同一证据。
     */
    public List<Map<String, Object>> merge(List<Map<String, Object>> existing, List<Map<String, Object>> incoming) {
        List<Map<String, Object>> merged = new ArrayList<Map<String, Object>>();
        Set<String> seen = new LinkedHashSet<String>();
        appendUnique(merged, seen, existing);
        appendUnique(merged, seen, incoming);
        return renumber(merged);
    }

    private void appendUnique(List<Map<String, Object>> target, Set<String> seen, List<Map<String, Object>> source) {
        if (source == null) {
            return;
        }
        for (Map<String, Object> citation : source) {
            if (citation == null) {
                continue;
            }
            String key = citation.get("sourceTable") + "|" + citation.get("sourceId") + "|"
                    + citation.get("fieldType") + "|" + citation.get("chunkNo");
            if (seen.add(key)) {
                target.add(citation);
            }
        }
    }

    /** 按给定顺序重编号，返回新列表（不修改入参），供跨工具调用合并使用。 */
    public List<Map<String, Object>> renumber(List<Map<String, Object>> citations) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        if (citations == null) {
            return result;
        }
        int index = 0;
        for (Map<String, Object> citation : citations) {
            if (citation == null) {
                continue;
            }
            index++;
            Map<String, Object> copy = new LinkedHashMap<String, Object>(citation);
            String fieldType = citation.get("fieldType") == null ? null : String.valueOf(citation.get("fieldType"));
            String diseaseName = citation.get("diseaseName") == null ? "" : String.valueOf(citation.get("diseaseName"));
            int chunkNo = citation.get("chunkNo") instanceof Number
                    ? ((Number) citation.get("chunkNo")).intValue() : 0;
            copy.put("index", Integer.valueOf(index));
            copy.put("label", buildLabel(index, diseaseName, fieldType, chunkNo));
            result.add(copy);
        }
        return result;
    }

    /** 渲染全局一致的证据块，用于喂给 LLM（编号与最终引用完全相同）。 */
    public String toEvidenceBlock(List<Map<String, Object>> citations) {
        StringBuilder builder = new StringBuilder();
        if (citations == null) {
            return "";
        }
        for (Map<String, Object> citation : citations) {
            if (citation == null) {
                continue;
            }
            String fieldType = citation.get("fieldType") == null ? null : String.valueOf(citation.get("fieldType"));
            builder.append("[").append(citation.get("index")).append("] ")
                    .append(citation.get("diseaseName") == null ? "" : citation.get("diseaseName"))
                    .append(" · ").append(fieldLabel(fieldType))
                    .append(" · 片段").append(citation.get("chunkNo") == null ? 0 : citation.get("chunkNo"))
                    .append("：").append(citation.get("snippet") == null ? "" : citation.get("snippet"))
                    .append("。出处：").append(citation.get("sourceName") == null ? "来源未登记" : citation.get("sourceName"));
            if (citation.get("sourceType") != null) {
                builder.append("（资料层级 ").append(citation.get("sourceType")).append("）");
            }
            if (citation.get("sourceVersion") != null) {
                builder.append("；核验版本：").append(citation.get("sourceVersion"));
            }
            if (citation.get("sourceUrl") != null) {
                builder.append("；原文：").append(citation.get("sourceUrl"));
            } else {
                builder.append("；原文链接未登记");
            }
            builder.append("\n");
        }
        return builder.toString().trim();
    }

    private String buildLabel(int index, String diseaseName, String fieldType, int chunkNo) {
        return "[" + index + "] " + diseaseName + " · " + fieldLabel(fieldType) + " · 片段" + chunkNo;
    }

    private String fieldLabel(String fieldType) {
        if (fieldType == null) {
            return "其他";
        }
        if ("SYMPTOM".equals(fieldType)) {
            return "症状";
        }
        if ("CAUSE".equals(fieldType)) {
            return "诱因";
        }
        if ("CONTROL".equals(fieldType)) {
            return "防治";
        }
        return "其他";
    }

    private String snippet(String content) {
        if (content == null) {
            return "";
        }
        String trimmed = content.trim();
        return trimmed.length() <= SNIPPET_LIMIT ? trimmed : trimmed.substring(0, SNIPPET_LIMIT) + "…";
    }
}
