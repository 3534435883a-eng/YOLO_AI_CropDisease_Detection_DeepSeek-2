package com.example.Ece.agent.rag;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 把检索命中转成可定位的引用（[n] -> 病名/字段/片段号）与 prompt 证据块。 */
@Component
public class CitationFormatter {

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
            citation.put("index", index);
            citation.put("diseaseName", chunk.getDiseaseName());
            citation.put("cropType", chunk.getCropType());
            citation.put("fieldType", chunk.getFieldType() == null ? null : chunk.getFieldType().name());
            citation.put("chunkNo", chunk.getChunkNo());
            citation.put("startOffset", chunk.getStartOffset());
            citation.put("score", item.getScore());
            citation.put("sourceTable", chunk.getSourceTable());
            citation.put("sourceId", chunk.getSourceId());
            citation.put("label", buildLabel(index, chunk));
            citations.add(citation);
        }
        return citations;
    }

    public String toPromptBlock(List<ScoredChunk> items) {
        StringBuilder builder = new StringBuilder();
        List<Map<String, Object>> citations = toCitations(items);
        for (int i = 0; i < citations.size(); i++) {
            Map<String, Object> citation = citations.get(i);
            ScoredChunk item = items.get(i);
            builder.append("[").append(citation.get("index")).append("] ")
                    .append(citation.get("diseaseName")).append(" · ")
                    .append(fieldLabel(item.getChunk().getFieldType())).append(" · 片段")
                    .append(citation.get("chunkNo")).append("：")
                    .append(item.getChunk().getContent())
                    .append("\n");
        }
        return builder.toString().trim();
    }

    private String buildLabel(int index, KnowledgeChunk chunk) {
        return "[" + index + "] " + chunk.getDiseaseName() + " · " + fieldLabel(chunk.getFieldType())
                + " · 片段" + chunk.getChunkNo();
    }

    private String fieldLabel(KnowledgeChunk.FieldType fieldType) {
        if (fieldType == null) {
            return "其他";
        }
        switch (fieldType) {
            case SYMPTOM:
                return "症状";
            case CAUSE:
                return "诱因";
            case CONTROL:
                return "防治";
            default:
                return "其他";
        }
    }
}
