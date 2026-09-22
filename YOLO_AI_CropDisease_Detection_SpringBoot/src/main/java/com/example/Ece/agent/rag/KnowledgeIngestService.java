package com.example.Ece.agent.rag;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 权威语料 ingest 管线（spec §23.3）：
 * 读取 → 规范化 → 切块 → **出处登记** → 向量化 → 幂等写入。
 *
 * <p>三条不可让步的纪律：</p>
 * <ol>
 *   <li>没有 {@code sourceName}/{@code version} 的来源在构造 {@link KnowledgeSource} 时即被拒绝；</li>
 *   <li>空文本记录计入 rejected 并留下原因，不静默丢弃；</li>
 *   <li>向量服务不可用时**仍然入库**，但报告标记 {@code embeddingDegraded}——宁可先有可检索的关键词索引，
 *       也不要因为向量服务没起来就丢掉整批语料。</li>
 * </ol>
 */
@Service
public class KnowledgeIngestService {

    private final KnowledgeChunker chunker;
    private final EmbeddingClient embeddingClient;
    private final KnowledgeSourceRepository sourceRepository;
    private final KnowledgeChunkRepository chunkRepository;

    public KnowledgeIngestService(KnowledgeChunker chunker, EmbeddingClient embeddingClient,
                                  KnowledgeSourceRepository sourceRepository,
                                  KnowledgeChunkRepository chunkRepository) {
        this.chunker = chunker;
        this.embeddingClient = embeddingClient;
        this.sourceRepository = sourceRepository;
        this.chunkRepository = chunkRepository;
    }

    public IngestReport ingest(KnowledgeSource source, List<IngestRecord> records) {
        IngestReport report = new IngestReport(source.getSourceCode());
        sourceRepository.save(source);

        // **全量替换语义**：先删掉该来源的旧块再重建。原实现按 content_hash 跳过已存在块，
        // 结果是切块规则或内容一旦调整就永远无法生效（而块表的唯一键是「来源表|来源ID|字段|块号」，
        // 直接改内容再插入会撞键）。删除后再取一次已存在哈希，用于**跨来源**去重。
        report.addRemoved(chunkRepository.deleteBySourceCode(source.getSourceCode()));

        Set<String> existing = chunkRepository.existingContentHashes();
        List<KnowledgeChunk> pending = new ArrayList<KnowledgeChunk>();
        List<double[]> embeddings = new ArrayList<double[]>();
        boolean degraded = false;

        if (records != null) {
            for (IngestRecord record : records) {
                if (record == null) {
                    report.reject("空记录");
                    continue;
                }
                List<KnowledgeChunk> chunks = chunker.chunk(record.getSourceTable(), record.getSourceId(),
                        record.getCropType(), record.getDiseaseName(), record.getFields());
                if (chunks.isEmpty()) {
                    report.reject("记录 " + record.getSourceId() + " 无有效文本");
                    continue;
                }
                report.accept();
                for (KnowledgeChunk chunk : chunks) {
                    if (existing.contains(chunk.getContentHash())) {
                        report.addSkipped(1);
                        continue;
                    }
                    pending.add(chunk);
                }
            }
        }

        for (KnowledgeChunk chunk : pending) {
            if (degraded) {
                embeddings.add(null);
                continue;
            }
            try {
                embeddings.add(embeddingClient.embed(chunk.getContent()));
            } catch (EmbeddingUnavailableException error) {
                degraded = true;
                report.markDegraded(embeddingClient.modelName());
                embeddings.add(null);
            }
        }
        if (!degraded) {
            report.setEmbeddingModel(embeddingClient.modelName());
        }

        int written = pending.isEmpty() ? 0
                : chunkRepository.saveAll(source.getSourceCode(), source.getAuthorityLevel(), pending,
                        embeddings, report.getEmbeddingModel());
        report.addNewlyWritten(written);
        report.setChunksTotal(existing.size() + written);
        return report;
    }
}
