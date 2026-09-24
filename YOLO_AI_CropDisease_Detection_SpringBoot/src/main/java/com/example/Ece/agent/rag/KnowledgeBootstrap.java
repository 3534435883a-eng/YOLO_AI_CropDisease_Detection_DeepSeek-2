package com.example.Ece.agent.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 知识库启动装配：**让知识在真实运行时真的进得去、索引真的建得起来**。
 *
 * <p>此前 ingest 服务与 {@code KnowledgeRetriever.rebuild} 只有测试调用，运行中的应用既没有入口灌数据、
 * 也没有入口建索引——表现为"表是空的、索引是空的、智能体永远拒答"。这个组件补上这一环：</p>
 * <ol>
 *   <li>库为空时，从项目历史病害库 ingest 一批真实语料（幂等：按内容哈希判重，重复启动不会重复写）；</li>
 *   <li>调用 {@link KnowledgeIndexService} 建索引，并打印可核对的摘要；</li>
 *   <li>库非空时只建索引，不重复 ingest。</li>
 * </ol>
 *
 * <p>知识库初始化失败**不允许拖垮平台**：推演、图像识别等业务与知识检索无关，因此这里只记警告。
 * 测试环境用 {@code agent.knowledge.bootstrap=false} 关闭，避免单元测试写开发库。</p>
 */
@Component
@ConditionalOnProperty(name = "agent.knowledge.bootstrap", havingValue = "true", matchIfMissing = true)
public class KnowledgeBootstrap implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(KnowledgeBootstrap.class);

    private final KnowledgeChunkRepository chunkRepository;
    private final KnowledgeIngestService ingestService;
    private final LegacyDiseaseKnowledgeReader legacyReader;
    private final CuratedCropKnowledgeReader curatedReader;
    private final KnowledgeSourceRepository sourceRepository;
    private final KnowledgeChunker chunker;
    private final KnowledgeIndexService indexService;

    public KnowledgeBootstrap(KnowledgeChunkRepository chunkRepository, KnowledgeIngestService ingestService,
                              LegacyDiseaseKnowledgeReader legacyReader, CuratedCropKnowledgeReader curatedReader,
                              KnowledgeSourceRepository sourceRepository, KnowledgeChunker chunker,
                              KnowledgeIndexService indexService) {
        this.chunkRepository = chunkRepository;
        this.ingestService = ingestService;
        this.legacyReader = legacyReader;
        this.curatedReader = curatedReader;
        this.sourceRepository = sourceRepository;
        this.chunker = chunker;
        this.indexService = indexService;
    }

    public void run(ApplicationArguments args) {
        try {
            List<KnowledgeChunk> existing = chunkRepository.loadAll();
            if (existing.isEmpty()) {
                LOGGER.info("知识库为空，开始从历史病害库 ingest（来源 {}，版本 {}）",
                        LegacyDiseaseKnowledgeReader.SOURCE_CODE, LegacyDiseaseKnowledgeReader.DATA_VERSION);
                IngestReport report = ingestService.ingest(legacyReader.source(), legacyReader.readAll());
                LOGGER.info("ingest 完成：accept={} reject={} 删除旧块={} 新写入={} 跳过重复={} 向量降级={}",
                        report.getAccepted(), report.getRejected(), report.getChunksRemoved(),
                        report.getChunksNewlyWritten(), report.getChunksSkipped(),
                        report.isEmbeddingDegraded());
                for (String reason : report.getRejectedReasons()) {
                    LOGGER.warn("ingest 拒绝记录：{}", reason);
                }
            }
            for (CuratedCropKnowledgeReader.SourceEntry entry : curatedReader.readAll()) {
                KnowledgeSource source = entry.getSource();
                int loadedChunks = 0;
                for (KnowledgeChunk chunk : existing) {
                    if (source.getSourceCode().equals(chunk.getSourceCode())) {
                        loadedChunks++;
                    }
                }
                IngestRecord record = entry.getRecord();
                int expectedChunks = chunker.chunk(record.getSourceTable(), record.getSourceId(),
                        record.getCropType(), record.getDiseaseName(), record.getFields()).size();
                if (sourceRepository.findByCode(source.getSourceCode(), source.getVersion()) == null
                        || loadedChunks != expectedChunks) {
                    IngestReport report = ingestService.ingest(source, Collections.singletonList(entry.getRecord()));
                    LOGGER.info("已载入核验资料 {}：{} 个知识块，向量降级={}", source.getSourceCode(),
                            report.getChunksNewlyWritten(), report.isEmbeddingDegraded());
                }
            }
            KnowledgeIndexService.KnowledgeLoadSummary summary = indexService.reload();
            LOGGER.info("知识库就绪：知识块 {} 条，存量向量 {} 条，已登记来源 {} 个（向量服务不可达时自动降级为纯关键词检索）",
                    summary.getChunkCount(), summary.getVectorCount(), summary.getSourceCount());
        } catch (RuntimeException error) {
            LOGGER.warn("知识库初始化失败，智能体检索将不可用（其余功能不受影响）：{}", error.toString());
        }
    }
}
