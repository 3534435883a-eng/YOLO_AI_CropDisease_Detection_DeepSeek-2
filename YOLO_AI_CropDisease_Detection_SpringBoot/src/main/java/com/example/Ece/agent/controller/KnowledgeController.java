package com.example.Ece.agent.controller;

import com.example.Ece.agent.rag.EmbeddingClient;
import com.example.Ece.agent.rag.EmbeddingUnavailableException;
import com.example.Ece.agent.rag.IngestReport;
import com.example.Ece.agent.rag.KnowledgeIndexService;
import com.example.Ece.agent.rag.KnowledgeIngestService;
import com.example.Ece.agent.rag.KnowledgeSource;
import com.example.Ece.agent.rag.KnowledgeSourceRepository;
import com.example.Ece.agent.rag.LegacyDiseaseKnowledgeReader;
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
    private EmbeddingClient embeddingClient;

    @GetMapping("/status")
    public Result<?> status() {
        KnowledgeIndexService.KnowledgeLoadSummary summary = knowledgeIndexService.reload();
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("chunkCount", Integer.valueOf(summary.getChunkCount()));
        payload.put("vectorCount", Integer.valueOf(summary.getVectorCount()));
        payload.put("sourceCount", Integer.valueOf(summary.getSourceCount()));
        payload.put("embeddingModel", embeddingClient.modelName());
        payload.put("embeddingAvailable", Boolean.valueOf(probeEmbedding()));
        List<Map<String, Object>> sources = new ArrayList<Map<String, Object>>();
        for (KnowledgeSource source : sourceRepository.findAll()) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("sourceCode", source.getSourceCode());
            row.put("sourceName", source.getSourceName());
            row.put("sourceType", source.getSourceType());
            row.put("authorityLevel", Integer.valueOf(source.getAuthorityLevel()));
            row.put("version", source.getVersion());
            row.put("licenseNote", source.getLicenseNote());
            sources.add(row);
        }
        payload.put("sources", sources);
        return Result.success(payload);
    }

    /**
     * 重新 ingest 历史病害库并重建索引。幂等：内容哈希已存在的块会被跳过（返回 skipped 计数）。
     * 权威语料（ICAMA / NY-T 标准 / 公开知识图谱）接入后续会复用同一管线。
     */
    @PostMapping("/reingest")
    public Result<?> reingest() {
        IngestReport report = ingestService.ingest(legacyReader.source(), legacyReader.readAll());
        KnowledgeIndexService.KnowledgeLoadSummary summary = knowledgeIndexService.reload();
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("accepted", Integer.valueOf(report.getAccepted()));
        payload.put("chunksRemoved", Integer.valueOf(report.getChunksRemoved()));
        payload.put("rejected", Integer.valueOf(report.getRejected()));
        payload.put("chunksNewlyWritten", Integer.valueOf(report.getChunksNewlyWritten()));
        payload.put("chunksSkipped", Integer.valueOf(report.getChunksSkipped()));
        payload.put("embeddingDegraded", Boolean.valueOf(report.isEmbeddingDegraded()));
        payload.put("chunkCount", Integer.valueOf(summary.getChunkCount()));
        payload.put("vectorCount", Integer.valueOf(summary.getVectorCount()));
        return Result.success(payload);
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