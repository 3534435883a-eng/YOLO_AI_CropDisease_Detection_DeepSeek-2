package com.example.Ece.agent.controller;

import com.example.Ece.agent.graph.KnowledgeGraphService;
import com.example.Ece.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识图谱接口。
 *
 * <p>图谱的边只收**列里没有的关系**（防治类别取自原文分节标记、亚型取自原文枚举、易混淆仅有出处者），
 * 列投影一类被实测否定的做法不做——{@code /summary} 会把"刻意没建模什么、为什么"一并返回。</p>
 */
@RestController
@RequestMapping("/ai/knowledge/graph")
public class KnowledgeGraphController {

    private final KnowledgeGraphService graphService;

    public KnowledgeGraphController(KnowledgeGraphService graphService) {
        this.graphService = graphService;
    }

    /** 图谱概况：节点/边计数、按关系分布，以及**刻意未建模的关系与原因**。 */
    @GetMapping("/summary")
    public Result<?> summary() {
        return Result.success(graphService.summary());
    }

    /**
     * 查节点的邻接边（双向）。
     *
     * @param type 节点类型：DISEASE / CROP / CONTROL_CATEGORY / SUBTYPE
     */
    @GetMapping
    public Result<?> neighbours(@RequestParam("type") String type,
                                @RequestParam("name") String name,
                                @RequestParam(value = "limit", required = false) Integer limit) {
        if (type == null || type.trim().isEmpty() || name == null || name.trim().isEmpty()) {
            return Result.error("GRAPH_QUERY_INVALID", "需要同时给出 type 与 name");
        }
        return Result.success(graphService.neighbours(type.trim().toUpperCase(), name.trim(),
                limit == null ? 50 : limit.intValue()));
    }

    /**
     * 从知识库原文重建图谱。
     *
     * <p>幂等；抽取无随机、无外部调用，同一份知识库必然得到同一张图。
     * 抽取规则变化时应先递增 {@code KnowledgeGraphExtractor.GRAPH_VERSION} 再刷新。</p>
     */
    @PostMapping("/refresh")
    public Result<?> refresh() {
        Map<String, Object> result = graphService.refresh();
        return Result.success(result);
    }

    /**
     * 诊断：给定疾病名，返回检索侧会拿到的那句图谱补充。
     *
     * <p>存在的理由：图谱补充走的是工具 {@code note} → 编排层 history 这条通道，
     * **SSE 事件里看不到**。没有这个接口，"图谱没被用上"与"用上了但模型没转述"就分不清——
     * 本项目已四次出现"建了没接线"，因此让它可被直接检验，而不是靠推断。</p>
     *
     * @param disease 疾病名，多个用英文逗号分隔
     */
    @GetMapping("/enrichment")
    public Result<?> enrichment(@RequestParam("disease") String disease) {
        List<String> names = new ArrayList<String>();
        for (String part : String.valueOf(disease).split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                names.add(trimmed);
            }
        }
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("queried", names);
        payload.put("enrichment", graphService.enrichmentFor(names));
        payload.put("note", "enrichment 为 null 表示图谱对这些疾病没有登记任何关系——"
                + "这是正常的（图谱只收列里没有的关系），不代表图谱未接线；"
                + "接线状态由 /summary 的 nodes/edges 与检索工具的 isGraphWired() 共同确认。");
        return Result.success(payload);
    }
}
