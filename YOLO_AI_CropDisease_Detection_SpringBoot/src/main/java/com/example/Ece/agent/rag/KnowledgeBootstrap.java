package com.example.Ece.agent.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库启动装配：**让知识在真实运行时真的进得去、索引真的建得起来**。
 *
 * <p>此前 ingest 服务与 {@code KnowledgeRetriever.rebuild} 只有测试调用，运行中的应用既没有入口灌数据、
 * 也没有入口建索引——表现为"表是空的、索引是空的、智能体永远拒答"。这个组件补上这一环：</p>
 * <ol>
 *   <li>库为空时，从项目历史病害库 ingest 一批真实语料（幂等：按内容哈希判重，重复启动不会重复写）；</li>
 *   <li>逐条核对人工整理的知识清单（层级 B 论文/机构页、层级 A 标准摘要条目），
 *       来源未登记或块数对不上时才重灌；</li>
 *   <li>调用 {@link KnowledgeIndexService} 建索引，并打印可核对的摘要。</li>
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
    private final CitedKnowledgeReader standardReader;
    private final KnowledgeSourceRepository sourceRepository;
    private final KnowledgeChunker chunker;
    private final KnowledgeIndexService indexService;

    public KnowledgeBootstrap(KnowledgeChunkRepository chunkRepository, KnowledgeIngestService ingestService,
                              LegacyDiseaseKnowledgeReader legacyReader, CuratedCropKnowledgeReader curatedReader,
                              CitedKnowledgeReader standardReader,
                              KnowledgeSourceRepository sourceRepository, KnowledgeChunker chunker,
                              KnowledgeIndexService indexService) {
        this.chunkRepository = chunkRepository;
        this.ingestService = ingestService;
        this.legacyReader = legacyReader;
        this.curatedReader = curatedReader;
        this.standardReader = standardReader;
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
            // 已入库的块数按来源代码统计一次，之后在内存里更新。
            // 不能在每轮循环里重新查库：`existing` 是启动时的一次快照，灌完标准后它仍是旧值，
            // 于是下一轮会误判"块数对不上"而每次启动都重灌（delete + insert）。
            Map<String, Integer> loadedByCode = countBySourceCode(existing);
            ingestIfChanged(curatedReader.readAll(), loadedByCode, "核验资料");
            ingestIfChanged(standardReader.readAll(), loadedByCode, "标准摘要");
            KnowledgeIndexService.KnowledgeLoadSummary summary = indexService.reload();
            LOGGER.info("知识库就绪：知识块 {} 条，存量向量 {} 条，已登记来源 {} 个（向量服务不可达时自动降级为纯关键词检索）",
                    summary.getChunkCount(), summary.getVectorCount(), summary.getSourceCount());
        } catch (RuntimeException error) {
            LOGGER.warn("知识库初始化失败，智能体检索将不可用（其余功能不受影响）：{}", error.toString());
        }
    }

    private Map<String, Integer> countBySourceCode(List<KnowledgeChunk> chunks) {
        Map<String, Integer> counts = new HashMap<String, Integer>();
        for (KnowledgeChunk chunk : chunks) {
            String code = chunk.getSourceCode();
            if (code == null) {
                continue;
            }
            Integer current = counts.get(code);
            counts.put(code, Integer.valueOf(current == null ? 1 : current.intValue() + 1));
        }
        return counts;
    }

    /**
     * 按**来源**分组核对并重灌。
     *
     * <p><b>为什么要分组，不能逐条灌</b>：{@code KnowledgeIngestService.ingest} 的语义是
     * **按来源代码全量替换**（内部先 {@code deleteBySourceCode} 再写）。逐条调用时，
     * 灌第二条会把第一条刚写进去的块删掉——2026-09-26 实测：15 条标准摘要条目只活下来 1 条，
     * 且没有报任何错。因此同一来源的全部记录必须**一次调用**灌完。</p>
     *
     * <p>判据是"来源未登记"或"已入库块数与当前切块总数不一致"——后者让**内容或切块规则改动后
     * 能自动生效**，而不必手工清库。</p>
     */
    private void ingestIfChanged(List<KnowledgeSourceEntry> entries, Map<String, Integer> loadedByCode,
                                 String label) {
        Map<String, List<IngestRecord>> bySourceCode = new LinkedHashMap<String, List<IngestRecord>>();
        Map<String, KnowledgeSource> sources = new LinkedHashMap<String, KnowledgeSource>();
        Map<String, Integer> expectedByCode = new LinkedHashMap<String, Integer>();
        for (KnowledgeSourceEntry entry : entries) {
            String code = entry.getSource().getSourceCode();
            sources.put(code, entry.getSource());
            List<IngestRecord> records = bySourceCode.get(code);
            if (records == null) {
                records = new ArrayList<IngestRecord>();
                bySourceCode.put(code, records);
                expectedByCode.put(code, Integer.valueOf(0));
            }
            records.add(entry.getRecord());
            expectedByCode.put(code, Integer.valueOf(expectedByCode.get(code).intValue()
                    + chunkCount(entry.getRecord())));
        }
        for (Map.Entry<String, List<IngestRecord>> group : bySourceCode.entrySet()) {
            String code = group.getKey();
            KnowledgeSource source = sources.get(code);
            int expected = expectedByCode.get(code).intValue();
            Integer loaded = loadedByCode.get(code);
            if (sourceRepository.findByCode(code, source.getVersion()) != null
                    && loaded != null && loaded.intValue() == expected) {
                continue;
            }
            IngestReport report = ingestService.ingest(source, group.getValue());
            loadedByCode.put(code, Integer.valueOf(expected));
            LOGGER.info("已载入{} {}：{} 条记录 → {} 个知识块（期望 {}），向量降级={}",
                    label, source.getSourceType(), code, Integer.valueOf(group.getValue().size()),
                    Integer.valueOf(report.getChunksNewlyWritten()), report.isEmbeddingDegraded());
        }
    }

    private int chunkCount(IngestRecord record) {
        return chunker.chunk(record.getSourceTable(), record.getSourceId(),
                record.getCropType(), record.getDiseaseName(), record.getFields()).size();
    }
}
