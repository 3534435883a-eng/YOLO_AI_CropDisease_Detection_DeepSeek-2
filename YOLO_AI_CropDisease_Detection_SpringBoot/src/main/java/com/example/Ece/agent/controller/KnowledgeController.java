package com.example.Ece.agent.controller;

import com.example.Ece.agent.rag.EmbeddingClient;
import com.example.Ece.agent.rag.EmbeddingUnavailableException;
import com.example.Ece.agent.rag.IngestReport;
import com.example.Ece.agent.rag.KnowledgeEntityLexicon;
import com.example.Ece.agent.rag.KnowledgeIndexService;
import com.example.Ece.agent.repository.JdbcVisionClassMapRepository;
import com.example.Ece.agent.rag.KnowledgeIngestService;
import com.example.Ece.agent.rag.KnowledgeSource;
import com.example.Ece.agent.rag.KnowledgeSourceRepository;
import com.example.Ece.agent.rag.LegacyDiseaseKnowledgeReader;
import com.example.Ece.agent.rag.CuratedCropKnowledgeReader;
import com.example.Ece.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库边界：状态查询与重新 ingest。
 *
 * <p>状态里同时给出**来源登记**（名称/类型/权威层级/版本）与向量服务可用性——这三项是复核
 * "回答依据从哪来"的最小信息集，演示与答辩都要能当场查到。</p>
 */
@RestController
@RequestMapping("/ai/knowledge")
public class KnowledgeController {

    @Resource
    private KnowledgeIndexService knowledgeIndexService;

    @Resource
    private KnowledgeSourceRepository sourceRepository;

    @Resource
    private KnowledgeIngestService ingestService;

    @Resource
    private LegacyDiseaseKnowledgeReader legacyReader;

    @Resource
    private CuratedCropKnowledgeReader curatedReader;

    @Resource
    private EmbeddingClient embeddingClient;

    @Resource
    private JdbcVisionClassMapRepository visionClassMapRepository;

    @Resource
    private KnowledgeEntityLexicon entityLexicon;

    /**
     * 向量服务探针缓存时长。探针本身是一次真实 {@code /embed} 往返（实测 14~32 ms），
     * 状态接口若被轮询就变成"每次轮询都打一次向量服务"；30 s 的缓存既让数字保持新鲜，
     * 又不会把状态查询变成负载。
     */
    private static final long EMBEDDING_PROBE_TTL_MS = 30_000L;

    private volatile Boolean embeddingProbeResult;
    private volatile long embeddingProbeAtMillis;

    /**
     * 知识库状态：块/向量/来源计数、索引快照时间、向量服务可用性、来源登记。
     *
     * <p><b>本接口不重建索引</b>（读上一次 {@link KnowledgeIndexService#reload()} 的快照），
     * 需要重建请调 {@code POST /ai/knowledge/reload} 或 {@code POST /ai/knowledge/reingest}。
     * 返回的 {@code indexLoadedAtMillis}/{@code indexReloadMillis} 让调用方看出这个快照有多旧。</p>
     */
    @GetMapping("/status")
    public Result<?> status() {
        KnowledgeIndexService.KnowledgeLoadSummary summary = knowledgeIndexService.currentSummary();
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("chunkCount", Integer.valueOf(summary.getChunkCount()));
        payload.put("vectorCount", Integer.valueOf(summary.getVectorCount()));
        payload.put("sourceCount", Integer.valueOf(summary.getSourceCount()));
        payload.put("indexReloadMillis", Long.valueOf(summary.getReloadMillis()));
        payload.put("indexLoadedAtMillis", Long.valueOf(summary.getLoadedAtMillis()));
        payload.put("indexVectorSearchAvailable", Boolean.valueOf(summary.isVectorSearchAvailable()));
        payload.put("embeddingModel", embeddingClient.modelName());
        payload.put("embeddingAvailable", Boolean.valueOf(embeddingAvailable()));
        // 探针年龄：0 表示本次刚实测；大于 0 表示复用了 TTL 内的缓存值——
        // 不写清楚的话，一个 20 秒前的"可用"会被当成此刻的结论。
        payload.put("embeddingProbeAgeMillis", Long.valueOf(embeddingProbeAgeMillis()));
        payload.put("embeddingProbeTtlMillis", Long.valueOf(EMBEDDING_PROBE_TTL_MS));
        List<Map<String, Object>> sources = new ArrayList<Map<String, Object>>();
        for (KnowledgeSource source : sourceRepository.findAll()) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("sourceCode", source.getSourceCode());
            row.put("sourceName", source.getSourceName());
            row.put("sourceType", source.getSourceType());
            row.put("authorityLevel", Integer.valueOf(source.getAuthorityLevel()));
            row.put("version", source.getVersion());
            row.put("url", source.getUrl());
            row.put("licenseNote", source.getLicenseNote());
            sources.add(row);
        }
        payload.put("sources", sources);
        return Result.success(payload);
    }

    /**
     * 只重建内存索引（不重新 ingest）。用于块表被外部改动后刷新，例如直接执行 SQL 修数据。
     *
     * <p>与 {@code /status} 的区别：这里是**唯一**会做重活的那个入口，因此放在 POST 下。</p>
     */
    @PostMapping("/reload")
    public Result<?> reload() {
        KnowledgeIndexService.KnowledgeLoadSummary summary = knowledgeIndexService.reload();
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("chunkCount", Integer.valueOf(summary.getChunkCount()));
        payload.put("vectorCount", Integer.valueOf(summary.getVectorCount()));
        payload.put("sourceCount", Integer.valueOf(summary.getSourceCount()));
        payload.put("reloadMillis", Long.valueOf(summary.getReloadMillis()));
        payload.put("loadedAtMillis", Long.valueOf(summary.getLoadedAtMillis()));
        payload.put("vectorSearchAvailable", Boolean.valueOf(summary.isVectorSearchAvailable()));
        return Result.success(payload);
    }

    /** 重新 ingest 历史病害库和已核验作物资料，并重建索引。 */
    @PostMapping("/reingest")
    public Result<?> reingest() {
        List<IngestReport> reports = new ArrayList<IngestReport>();
        reports.add(ingestService.ingest(legacyReader.source(), legacyReader.readAll()));
        for (CuratedCropKnowledgeReader.SourceEntry entry : curatedReader.readAll()) {
            reports.add(ingestService.ingest(entry.getSource(), java.util.Collections.singletonList(entry.getRecord())));
        }
        KnowledgeIndexService.KnowledgeLoadSummary summary = knowledgeIndexService.reload();
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        int accepted = 0;
        int removed = 0;
        int rejected = 0;
        int written = 0;
        int skipped = 0;
        long deleteMillis = 0;
        long embedMillis = 0;
        long writeMillis = 0;
        boolean degraded = false;
        for (IngestReport report : reports) {
            accepted += report.getAccepted();
            removed += report.getChunksRemoved();
            rejected += report.getRejected();
            written += report.getChunksNewlyWritten();
            skipped += report.getChunksSkipped();
            deleteMillis += report.getDeleteMillis();
            embedMillis += report.getEmbedMillis();
            writeMillis += report.getWriteMillis();
            degraded |= report.isEmbeddingDegraded();
        }
        payload.put("accepted", Integer.valueOf(accepted));
        payload.put("chunksRemoved", Integer.valueOf(removed));
        payload.put("rejected", Integer.valueOf(rejected));
        payload.put("chunksNewlyWritten", Integer.valueOf(written));
        payload.put("chunksSkipped", Integer.valueOf(skipped));
        payload.put("embeddingDegraded", Boolean.valueOf(degraded));
        // 阶段耗时：定位 ingest 瓶颈用。**别靠猜**——实测 338 块里最大的那段不是向量化。
        payload.put("deleteMillis", Long.valueOf(deleteMillis));
        payload.put("embedMillis", Long.valueOf(embedMillis));
        payload.put("writeMillis", Long.valueOf(writeMillis));
        payload.put("indexReloadMillis", Long.valueOf(summary.getReloadMillis()));
        payload.put("chunkCount", Integer.valueOf(summary.getChunkCount()));
        payload.put("vectorCount", Integer.valueOf(summary.getVectorCount()));
        return Result.success(payload);
    }

    /**
     * 视觉检测类别与知识库条目的对应关系及覆盖缺口。
     *
     * <p>回答"模型能看到什么、知识库能解释多少"。映射只收录经过核验的条目
     * （依据见 docs/vision-class-kb-mapping.md），未核验的 matchRule 为 NONE。</p>
     */
    @GetMapping("/vision-map")
    public Result<?> visionMap() {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("summary", visionClassMapRepository.summarize());
        payload.put("items", visionClassMapRepository.findAll());
        return Result.success(payload);
    }

    /**
     * 作物实体别名词典：别名 → 规范作物名。
     *
     * <p>来源如实标注：人工整理的通用农艺术称，非权威来源；词典内部逐条记录了该别名
     * 是否在知识库原文中出现过（如"冬小麦"×5、"苹果树"×7）。</p>
     */
    @GetMapping("/entities")
    public Result<?> entities() {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("version", entityLexicon.getVersion());
        payload.put("aliasToCrop", entityLexicon.getAliasTextByCrop());
        payload.put("note", "人工词典（通用农艺术称，非权威来源）；别名在知识库原文中的出现次数见 docs/knowledge-entity-lexicon.md");
        return Result.success(payload);
    }

    /** 向量服务是否可用（带 TTL 缓存，避免状态接口被轮询时反复打向量服务）。 */
    private boolean embeddingAvailable() {
        Long age = Long.valueOf(embeddingProbeAgeMillis());
        Boolean cached = embeddingProbeResult;
        if (cached != null && age.longValue() < EMBEDDING_PROBE_TTL_MS) {
            return cached.booleanValue();
        }
        boolean fresh = probeEmbedding();
        embeddingProbeResult = Boolean.valueOf(fresh);
        embeddingProbeAtMillis = System.currentTimeMillis();
        return fresh;
    }

    /** 上次探针距今毫秒数；从未探针时为 0（与"刚刚探过"同义，都会在调用前被 re-probe）。 */
    private long embeddingProbeAgeMillis() {
        long at = embeddingProbeAtMillis;
        return at == 0L ? 0L : System.currentTimeMillis() - at;
    }

    private boolean probeEmbedding() {
        try {
            embeddingClient.embed("番茄");
            return true;
        } catch (EmbeddingUnavailableException error) {
            return false;
        }
    }
}
