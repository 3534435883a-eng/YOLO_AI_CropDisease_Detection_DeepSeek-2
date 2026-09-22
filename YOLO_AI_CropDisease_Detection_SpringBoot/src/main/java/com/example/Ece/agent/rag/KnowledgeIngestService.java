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

    /** 单次批量向量化的条数，取值依据见 {@link EmbeddingClient#DEFAULT_BATCH_SIZE}。 */
    public static final int EMBED_BATCH_SIZE = EmbeddingClient.DEFAULT_BATCH_SIZE;

    /** 逐条重试时连续失败多少条即认定向量服务不可用（避免超时 × 剩余条数）。 */
    public static final int EMBED_FAILURE_LIMIT = 2;

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
        long deleteStartedAt = System.currentTimeMillis();
        report.addRemoved(chunkRepository.deleteBySourceCode(source.getSourceCode()));
        report.addDeleteMillis(System.currentTimeMillis() - deleteStartedAt);

        Set<String> existing = chunkRepository.existingContentHashes();
        List<KnowledgeChunk> pending = new ArrayList<KnowledgeChunk>();
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

        long embedStartedAt = System.currentTimeMillis();
        List<double[]> embeddings = embedAll(pending);
        report.addEmbedMillis(System.currentTimeMillis() - embedStartedAt);
        for (int i = 0; i < embeddings.size(); i++) {
            if (embeddings.get(i) == null) {
                degraded = true;
                break;
            }
        }
        if (degraded) {
            report.markDegraded(embeddingClient.modelName());
        } else {
            report.setEmbeddingModel(embeddingClient.modelName());
        }

        long writeStartedAt = System.currentTimeMillis();
        int written = pending.isEmpty() ? 0
                : chunkRepository.saveAll(source.getSourceCode(), source.getAuthorityLevel(), pending,
                        embeddings, report.getEmbeddingModel());
        report.addWriteMillis(System.currentTimeMillis() - writeStartedAt);
        report.addNewlyWritten(written);
        report.setChunksTotal(existing.size() + written);
        return report;
    }

    /**
     * 向量化待入库的块，返回与 {@code pending} **一一对应**的向量表（失败位置为 {@code null}）。
     *
     * <p>三段策略：</p>
     * <ol>
     *   <li><b>优先整批</b>（每批 {@link #EMBED_BATCH_SIZE} 条单次请求）。本地实测 32 条 79 ms，
     *       而逐条 32 次约 1,150 ms——338 块从 8.5 s 降到 1 s 量级（spec §29.3 的优化点）。</li>
     *   <li><b>整批失败则退回逐条</b>：一条超长/异常文本不应该让同批另外 31 条一起丢掉向量。</li>
     *   <li><b>连续 {@link #EMBED_FAILURE_LIMIT} 条都失败即认定服务不可用并停止后续请求</b>——
     *       否则一个没起来的 Flask 服务会按"剩余条数 × 3 s 读超时"把 ingest 拖成几十分钟。</li>
     * </ol>
     *
     * <p>部分失败也如实标记降级：块仍入库（可关键词检索），但向量确实缺了，
     * 这是**如实记录**而不是可以静默的事。</p>
     */
    private List<double[]> embedAll(List<KnowledgeChunk> pending) {
        List<double[]> embeddings = new ArrayList<double[]>();
        int index = 0;
        boolean serviceDown = false;
        while (index < pending.size()) {
            if (serviceDown) {
                embeddings.add(null);
                index++;
                continue;
            }
            int end = Math.min(index + EMBED_BATCH_SIZE, pending.size());
            List<double[]> batch = tryBatch(pending, index, end);
            if (batch != null) {
                embeddings.addAll(batch);
                index = end;
                continue;
            }
            int consecutiveFailures = 0;
            while (index < end && consecutiveFailures < EMBED_FAILURE_LIMIT) {
                try {
                    embeddings.add(embeddingClient.embed(pending.get(index).getContent()));
                    consecutiveFailures = 0;
                } catch (EmbeddingUnavailableException error) {
                    embeddings.add(null);
                    consecutiveFailures++;
                }
                index++;
            }
            serviceDown = consecutiveFailures >= EMBED_FAILURE_LIMIT;
        }
        return embeddings;
    }

    /** 单批向量化；失败或返回条数不符时返回 {@code null}，由调用方退回逐条。 */
    private List<double[]> tryBatch(List<KnowledgeChunk> pending, int from, int to) {
        List<String> texts = new ArrayList<String>();
        for (int i = from; i < to; i++) {
            texts.add(pending.get(i).getContent());
        }
        try {
            List<double[]> vectors = embeddingClient.embedBatch(texts);
            return vectors != null && vectors.size() == texts.size() ? vectors : null;
        } catch (EmbeddingUnavailableException error) {
            return null;
        }
    }
}
