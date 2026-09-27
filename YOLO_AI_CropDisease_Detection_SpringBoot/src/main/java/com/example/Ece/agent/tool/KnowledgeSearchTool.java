package com.example.Ece.agent.tool;

import com.example.Ece.agent.graph.KnowledgeGraphService;
import com.example.Ece.agent.rag.CitationFormatter;
import com.example.Ece.agent.rag.KnowledgeChunker;
import com.example.Ece.agent.rag.KnowledgeRetriever;
import com.example.Ece.agent.rag.RetrievalResult;
import com.example.Ece.agent.rag.ScoredChunk;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 知识检索工具：返回引用列表、证据块与降级标记。 */
@Component
public class KnowledgeSearchTool implements AgentTool {

    public static final String NAME = "knowledge.search";

    private final KnowledgeRetriever retriever;
    private final CitationFormatter citationFormatter;
    /** 知识图谱（可选注入）。为 null 时不做图谱补充，检索行为与接线前一致。 */
    private KnowledgeGraphService graphService;

    public KnowledgeSearchTool(KnowledgeRetriever retriever, CitationFormatter citationFormatter) {
        this.retriever = retriever;
        this.citationFormatter = citationFormatter;
    }

    /**
     * 可选注入知识图谱服务。
     *
     * <p>用 setter 而不是构造器：{@code docs/knowledge-entity-lexicon.md} §五 记录过
     * "给 {@code KnowledgeRetriever} 增加第二个构造器后 Spring 找不到无参构造器、应用整体启动失败"，
     * 以及"只跑单测会漏掉这类错误"。本项目的既有写法是 {@code @Autowired(required = false)} setter
     * （见 {@code AgentToolRegistry}），既避免该风险，也让单元测试可以用两参构造器继续构造。
     * 未注入时检索照常工作，只是不做图谱补充。</p>
     */
    @Autowired(required = false)
    public void setGraphService(KnowledgeGraphService graphService) {
        this.graphService = graphService;
    }

    /**
     * 知识图谱是否已接线。
     *
     * <p>公开出来是**刻意的**：本项目已四次出现"表/枚举建好了但零调用"（{@code agent_step_trace}、
     * {@code ToolPermission.DRAFT}、{@code agent_parameter_source}、图谱本身），
     * 而 setter 注入失败时检索会静默降级为"没有图谱补充"——**没有痕迹的降级正是这种问题的温床**。
     * 有了它，{@code AgentToolRegistryWiringTest} 能直接断言注入成功，部署侧也可据此做健康检查。</p>
     */
    public boolean isGraphWired() {
        return graphService != null;
    }

    public String name() {
        return NAME;
    }

    /**
     * 工具自述。
     *
     * <p><b>2026-09-27 修：原描述只写"作物病害知识库…用于回答病害识别、症状判断与防治方案问题"，
     * 而这句话把规划步引开了。</b>实测「我这棚番茄刚定植一周，接下来这一周具体该做哪些农事管理？」
     * 时，规划步**只调了 {@code platform.greenhouseState}、从未调用本工具**，
     * 于是回答"知识库当前缺少该主题的依据"——可库里当时已有 53 块番茄农事知识
     *（缓苗期环境、定植后灌水、整枝授粉疏果…）。**系统拒答了一个它答得出的问题。**</p>
     *
     * <p>根因是描述与库内容脱节：库在 2026-09-26 起已加入栽培/水肥/环境/四类防治/成本等类别，
     * 而描述还停在"病害库"。这与 {@code prescription.draft} 那次是**同一类错误的两个方向**——
     * 那次是工具给自己**揽了**能力（宣称能答用量，于是规划步不再检索），
     * 这次是工具**少报了**能力（宣称只管病害，于是农事问题根本不检索）。
     * 两处都说明：**工具自述是规划步唯一的决策依据，必须与库的真实内容对齐。**</p>
     */
    public String description() {
        return "检索本地农业知识库，返回带出处的证据片段。库内不止病害，还含："
                + "栽培管理（育苗、定植、缓苗、整枝、授粉、疏果、采收）、水肥管理（灌溉与施肥制度）、"
                + "环境调控阈值（昼温/夜温/土壤与空气湿度）、病虫害四类防治（农业/物理/生物/化学）与登记用药、"
                + "以及农资价格。用途包括病害识别与防治，**也包括「这一周该怎么管」「定植后怎么缓苗」"
                + "「膨果期怎么浇水追肥」这类农事管理问题**。"
                + "拿不准该不该检索时**应当检索**：本工具是判断「有没有依据」的唯一入口，"
                + "不检索就直接说「依据不足」，会把库里已有的知识挡在外面。";
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
        StringBuilder note = new StringBuilder();
        String unknownEntity = result.getUnknownEntityToken();
        if (unknownEntity != null) {
            // 缺的是**具体的名字**，就把它说出来。笼统的"依据不足"会让农户以为是自己问法
            // 有问题、反复换说法；指出「黄秆虫」这个条目没收录，才是可执行的反馈。
            String message = "知识库里没有「" + unknownEntity + "」这个病/虫的条目"
                    + "（按本次问到的作物范围检索），因此无法就它给出依据。"
                    + "本系统不用名字相近的其他病害替代作答——那等于答非所问。"
                    + "建议核实名称、改问该作物已收录的病害，或联系当地农技人员。";
            output.put("unknownEntity", unknownEntity);
            output.put("note", message);
            // 走**工具级终态**，不做查询改写重试：改写很可能把「黄秆虫」换成库里有条目的
            // 另一种害虫，于是模型会针对那个害虫组织一篇有引用、可核对、**但答非所问**的回答。
            // 检索层已确认这个名字不在语料里，换个说法不会让它出现，重试只是把拒答变成错答。
            output.put("terminal", Boolean.TRUE);
            output.put("terminalReason", "ENTITY_NOT_IN_KNOWLEDGE");
            output.put("terminalAnswer", message);
            output.put("inputDigest",
                    KnowledgeChunker.sha256(query + "|" + (crop == null ? "" : crop) + "|" + topN));
            return output;
        }
        if (lowScore) {
            note.append(result.getBm25HitCount() == 0
                    ? "知识库里没有与「" + query + "」匹配的关键词依据（关键词零命中）"
                    : "检索到 " + result.getItems().size() + " 条但相关性不足（查询词覆盖率 "
                            + String.format(Locale.ROOT, "%.2f", result.getQueryCoverage())
                            + "），不足以作为结论依据");
        }
        // 知识图谱补充：把**列里没有**的关系（原文登记的防治类别、有出处的易混淆对）拼进来。
        // 放在 note 是因为编排层把 note 完整传给模型；而引用块每条来源只有 160 字，放不下这类附加信息。
        // 未注入图谱服务时该项为 null，检索行为与接线前完全一致。
        String enrichment = graphService == null ? null : graphService.enrichmentFor(diseaseNamesOf(result));
        if (enrichment != null) {
            output.put("graphEnrichment", enrichment);
            if (note.length() > 0) {
                note.append("。");
            }
            note.append(enrichment);
        }
        if (note.length() > 0) {
            output.put("note", note.toString());
        }
        output.put("inputDigest", KnowledgeChunker.sha256(query + "|" + (crop == null ? "" : crop) + "|" + topN));
        return output;
    }

    /** 本次命中涉及的疾病名（去重、保序）。 */
    private List<String> diseaseNamesOf(RetrievalResult result) {
        List<String> names = new ArrayList<String>();
        for (ScoredChunk item : result.getItems()) {
            if (item == null || item.getChunk() == null) {
                continue;
            }
            String disease = item.getChunk().getDiseaseName();
            if (disease != null && !disease.trim().isEmpty() && !names.contains(disease)) {
                names.add(disease.trim());
            }
        }
        return names;
    }
}
