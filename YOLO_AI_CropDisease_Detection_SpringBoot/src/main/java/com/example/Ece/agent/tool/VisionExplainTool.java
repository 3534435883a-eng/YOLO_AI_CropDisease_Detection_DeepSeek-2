package com.example.Ece.agent.tool;

import com.example.Ece.agent.rag.CitationFormatter;
import com.example.Ece.agent.rag.KnowledgeChunker;
import com.example.Ece.agent.rag.KnowledgeEntityLexicon;
import com.example.Ece.agent.rag.KnowledgeRetriever;
import com.example.Ece.agent.rag.RetrievalResult;
import com.example.Ece.agent.repository.JdbcVisionClassMapRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 视觉检测类别解释工具：把"模型检出了什么"接到"知识库有什么依据"上。
 *
 * <p><b>为什么需要</b>：在此之前识别结果与知识库是两条互不相连的路——检测只产出一个类别标签，
 * 而智能体只能读用户打的字，无法解释"摄像头刚检出的这个类别是什么、依据是什么"。</p>
 *
 * <p><b>只走已核验的映射</b>：类别 → 知识库条目 的对应关系来自 {@code agent_vision_class_map}，
 * 该表只收录经过证据化核验的映射（见 docs/vision-class-kb-mapping.md），其余为 {@code NONE}。
 * 命中 {@code NONE} 时**明确回答"知识库中没有可核对的对应条目"，绝不猜测病名**。</p>
 *
 * <p><b>歧义处理</b>：同一类别中文名可能在多个作物模型中出现（实测"早疫病"同时存在于番茄与马铃薯模型）。
 * 此时若调用方给了作物就用作物消歧；没给就返回候选列表并要求补充作物，而不是随便挑一个。</p>
 */
@Component
public class VisionExplainTool implements AgentTool {

    public static final String NAME = "vision.explain";
    private static final int DEFAULT_TOP_N = 5;

    private final JdbcVisionClassMapRepository classMapRepository;
    private final KnowledgeRetriever retriever;
    private final CitationFormatter citationFormatter;
    private final KnowledgeEntityLexicon entityLexicon;

    public VisionExplainTool(JdbcVisionClassMapRepository classMapRepository, KnowledgeRetriever retriever,
                             CitationFormatter citationFormatter, KnowledgeEntityLexicon entityLexicon) {
        this.classMapRepository = classMapRepository;
        this.retriever = retriever;
        this.citationFormatter = citationFormatter;
        this.entityLexicon = entityLexicon;
    }

    public String name() {
        return NAME;
    }

    public String description() {
        return "把视觉检测模型的类别标签（如 Early_Blight(早疫病)、Septoria(壳针孢病)）对应到本地知识库条目，"
                + "并返回该病害的症状/诱因/防治证据。用于回答“摄像头或图片识别出某种病害，这是什么、该怎么办”。"
                + "若该类别在知识库中没有可核对的对应条目，会明确说明而不会猜测病名。";
    }

    public ToolPermission permission() {
        return ToolPermission.READ_ONLY;
    }

    public String inputSchemaJson() {
        return "{\"type\":\"object\",\"properties\":{"
                + "\"classLabel\":{\"type\":\"string\",\"description\":\"检测模型输出的类别标签，例如 Early_Blight(早疫病) 或 早疫病\"},"
                + "\"crop\":{\"type\":\"string\",\"description\":\"作物名称（用于消歧），可选\"},"
                + "\"topN\":{\"type\":\"integer\",\"description\":\"返回条数，默认 5\"}},"
                + "\"required\":[\"classLabel\"]}";
    }

    public Map<String, Object> execute(Map<String, Object> input) throws ToolException {
        if (input == null) {
            throw new ToolException("input is required");
        }
        Object rawLabel = input.get("classLabel");
        String label = rawLabel == null ? "" : String.valueOf(rawLabel).trim();
        if (label.isEmpty()) {
            throw new ToolException("classLabel is required");
        }
        String crop = input.get("crop") == null ? null : String.valueOf(input.get("crop"));
        String canonicalCrop = entityLexicon == null ? crop : entityLexicon.canonicalizeCrop(crop);
        int topN = DEFAULT_TOP_N;
        if (input.get("topN") instanceof Number) {
            topN = ((Number) input.get("topN")).intValue();
        }

        List<Map<String, Object>> candidates = match(label, canonicalCrop);

        Map<String, Object> output = new LinkedHashMap<String, Object>();
        if (candidates.isEmpty()) {
            return unavailable(output, "未在视觉类别映射表中找到该类别（classLabel=" + label + "）",
                    "该标签不在 9 个检测模型的 56 个类别之内；不得据此推断病害。", null,
                    "KNOWLEDGE_INSUFFICIENT",
                    "资料库不足：检测类别「" + label + "」不在已登记的 56 个模型类别之内，"
                            + "无法给出有依据的解释。请先确认类别标签是否正确，或补充相应知识来源。");
        }

        Set<String> diseases = new LinkedHashSet<String>();
        List<String> labels = new ArrayList<String>();
        for (Map<String, Object> row : candidates) {
            labels.add(String.valueOf(row.get("classLabel")) + "（模型 " + row.get("modelCode") + "）");
            Object disease = row.get("kbDiseaseName");
            if (disease != null && !String.valueOf(disease).trim().isEmpty()) {
                diseases.add(String.valueOf(disease).trim());
            }
        }

        // 歧义：同名类别落在多个作物上且调用方未指定作物
        if (diseases.size() > 1) {
            List<String> options = new ArrayList<String>(diseases);
            return unavailable(output, "该类别在多个作物的模型中存在，且未指定作物",
                    "候选：" + String.join("、", options) + "。请先确认作物后再查询，不得直接选定其中一个。",
                    options, "NEED_CROP",
                    "需要先确认作物：「" + label + "」在多个作物的模型中都存在（候选："
                            + String.join("、", options) + "）。请说明作物后我再查询。");
        }

        if (diseases.isEmpty()) {
            Map<String, Object> row = candidates.get(0);
            return unavailable(output,
                    "检测类别 " + row.get("classLabel") + "（模型 " + row.get("modelCode") + "）在知识库中没有可核对的对应条目",
                    "映射规则为 " + row.get("matchRule") + "：" + row.get("evidence"),
                    null, "KNOWLEDGE_INSUFFICIENT",
                    "资料库不足：知识库里没有检测类别「" + row.get("labelZh") + "」（模型 " + row.get("modelCode")
                            + "）可核对的条目，无法给出诊断或防治建议。"
                            + "建议人工核实，或先补充该病害/虫害的知识来源。");
        }

        String disease = diseases.iterator().next();
        Map<String, Object> row = candidates.get(0);
        for (Map<String, Object> candidate : candidates) {
            if (disease.equals(candidate.get("kbDiseaseName"))) {
                row = candidate;
                break;
            }
        }
        String diseaseCrop = row.get("cropType") == null ? null : String.valueOf(row.get("cropType"));

        RetrievalResult result = retriever.retrieve(disease, diseaseCrop, topN);
        output.put("citations", citationFormatter.toCitations(result.getItems()));
        output.put("items", result.getItems());
        output.put("promptBlock", citationFormatter.toPromptBlock(result.getItems()));
        output.put("degraded", Boolean.valueOf(result.isDegraded()));
        output.put("degradedReason", result.getDegradedReason());
        output.put("lowScore", Boolean.valueOf(retriever.isLowScore(result)));
        output.put("mapping", mapping(row));
        output.put("note", "检测类别 " + row.get("classLabel") + "（模型 " + row.get("modelCode")
                + "）对应知识库条目《" + disease + "》；映射规则 " + row.get("matchRule")
                + "，依据：" + row.get("evidence"));
        output.put("inputDigest", KnowledgeChunker.sha256(NAME + "|" + label + "|"
                + (canonicalCrop == null ? "" : canonicalCrop) + "|" + topN));
        return output;
    }

    /**
     * 无可用依据时的统一返回，并给出**终止信号**。
     *
     * <p><b>为什么必须终止</b>：下面三类情况都不是"再检索一次就能答"的问题，而是资料不足或信息不全：
     * 类别不在模型类别表内、同名类别跨作物未给作物、类别没有可核对的知识条目。
     * 若让循环继续，模型会拿相近主题的证据硬答——实测问"潜叶虫"时它检索到 7 条番茄病害并试图作答，
     * 虽然最终自己声明"不能用相近主题内容替代作答"，但那种 DONE + 无关引用的结果没有意义。</p>
     *
     * <p>因此这里直接给出 {@code terminal=true} 与最终答复文案，编排层据此**立即结束本轮、不再检索**，
     * 用户看到的是一句明确的"资料库不足"或"请先确认作物"。</p>
     */
    private Map<String, Object> unavailable(Map<String, Object> output, String reason, String note,
                                            List<String> candidates, String terminalReason,
                                            String terminalAnswer) {
        output.put("lowScore", Boolean.TRUE);
        output.put("citations", new ArrayList<Map<String, Object>>());
        output.put("mapping", null);
        output.put("note", reason + "：" + note);
        output.put("terminal", Boolean.TRUE);
        output.put("terminalReason", terminalReason);
        output.put("terminalAnswer", terminalAnswer);
        if (candidates != null) {
            output.put("candidates", candidates);
        }
        return output;
    }

    private Map<String, Object> mapping(Map<String, Object> row) {
        Map<String, Object> mapping = new LinkedHashMap<String, Object>();
        mapping.put("modelCode", row.get("modelCode"));
        mapping.put("classLabel", row.get("classLabel"));
        mapping.put("kbDiseaseName", row.get("kbDiseaseName"));
        mapping.put("matchRule", row.get("matchRule"));
        mapping.put("evidence", row.get("evidence"));
        mapping.put("sourceUrl", row.get("sourceUrl"));
        return mapping;
    }

    /** 匹配规则：完全一致 → 中文标签一致 → 英文标签一致（忽略大小写）→ 中文标签互为子串；再按作物过滤。 */
    private List<Map<String, Object>> match(String label, String canonicalCrop) {
        List<Map<String, Object>> exact = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> loose = new ArrayList<Map<String, Object>>();
        String lower = label.toLowerCase();
        for (Map<String, Object> row : classMapRepository.findAll()) {
            String classLabel = row.get("classLabel") == null ? "" : String.valueOf(row.get("classLabel"));
            String labelZh = row.get("labelZh") == null ? "" : String.valueOf(row.get("labelZh"));
            String labelEn = row.get("labelEn") == null ? "" : String.valueOf(row.get("labelEn"));
            if (label.equals(classLabel) || label.equals(labelZh) || lower.equals(labelEn.toLowerCase())) {
                exact.add(row);
            } else if ((!labelZh.isEmpty() && label.contains(labelZh))
                    || (!labelZh.isEmpty() && labelZh.contains(label) && label.length() >= 2)) {
                loose.add(row);
            }
        }
        List<Map<String, Object>> matched = exact.isEmpty() ? loose : exact;
        if (canonicalCrop == null || canonicalCrop.trim().isEmpty()) {
            return matched;
        }
        List<Map<String, Object>> filtered = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> row : matched) {
            String cropType = row.get("cropType") == null ? "" : String.valueOf(row.get("cropType"));
            if (cropType.equals(canonicalCrop.trim()) || cropType.contains(canonicalCrop.trim())) {
                filtered.add(row);
            }
        }
        return filtered.isEmpty() ? matched : filtered;
    }
}