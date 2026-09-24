package com.example.Ece.agent.tool;

import com.example.Ece.agent.rag.CitationFormatter;
import com.example.Ece.agent.rag.KnowledgeChunker;
import com.example.Ece.agent.rag.KnowledgeRetriever;
import com.example.Ece.agent.rag.RetrievalResult;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** 知识检索工具：返回引用列表、证据块与降级标记。 */
@Component
public class KnowledgeSearchTool implements AgentTool {

    public static final String NAME = "knowledge.search";

    private final KnowledgeRetriever retriever;
    private final CitationFormatter citationFormatter;

    public KnowledgeSearchTool(KnowledgeRetriever retriever, CitationFormatter citationFormatter) {
        this.retriever = retriever;
        this.citationFormatter = citationFormatter;
    }

    public String name() {
        return NAME;
    }

    public String description() {
        return "检索本地作物病害知识库，返回带出处的证据片段；用于回答病害识别、症状判断与防治方案问题。";
    }

    public ToolPermission permission() {
        return ToolPermission.READ_ONLY;
    }

    public String inputSchemaJson() {
        return "{\"type\":\"object\",\"properties\":{"
                + "\"query\":{\"type\":\"string\",\"description\":\"检索问题或症状描述\"},"
                + "\"crop\":{\"type\":\"string\",\"description\":\"作物名称，可选\"},"
                + "\"topN\":{\"type\":\"integer\",\"description\":\"返回条数，默认 5\"}},"
                + "\"required\":[\"query\"]}";
    }

    public Map<String, Object> execute(Map<String, Object> input) throws ToolException {
        if (input == null) {
            throw new ToolException("input is required");
        }
        Object rawQuery = input.get("query");
        String query = rawQuery == null ? "" : String.valueOf(rawQuery).trim();
        if (query.isEmpty()) {
            throw new ToolException("query is required");
        }
        String crop = input.get("crop") == null ? null : String.valueOf(input.get("crop"));
        int topN = 5;
        Object rawTopN = input.get("topN");
        if (rawTopN instanceof Number) {
            topN = ((Number) rawTopN).intValue();
        }
        // 工具参数由模型生成，必须设上限，避免异常规划造成过大响应和无谓检索。
        topN = Math.max(1, Math.min(10, topN));
        RetrievalResult result = retriever.retrieve(query, crop, topN);
        boolean lowScore = retriever.isLowScore(result);
        Map<String, Object> output = new LinkedHashMap<String, Object>();
        output.put("citations", citationFormatter.toCitations(result.getItems()));
        output.put("items", result.getItems());
        output.put("promptBlock", citationFormatter.toPromptBlock(result.getItems()));
        output.put("degraded", Boolean.valueOf(result.isDegraded()));
        output.put("degradedReason", result.getDegradedReason());
        output.put("topScore", Double.valueOf(result.getTopScore()));
        output.put("lowScore", Boolean.valueOf(lowScore));
        // 无可用证据时给出**具体原因**：编排层会把它拼进拒答文案。
        // 用户看到的不该只是一句笼统的"没有可靠依据"，而要能分辨是"库里根本没这段"还是
        // "检索到了但相关性不够"——前者是资料缺口，后者往往换个问法就有。
        if (lowScore) {
            output.put("note", result.getBm25HitCount() == 0
                    ? "知识库里没有与「" + query + "」匹配的关键词依据（关键词零命中）"
                    : "检索到 " + result.getItems().size() + " 条但相关性不足（查询词覆盖率 "
                            + String.format(Locale.ROOT, "%.2f", result.getQueryCoverage())
                            + "），不足以作为结论依据");
        }
        output.put("inputDigest", KnowledgeChunker.sha256(query + "|" + (crop == null ? "" : crop) + "|" + topN));
        return output;
    }
}
