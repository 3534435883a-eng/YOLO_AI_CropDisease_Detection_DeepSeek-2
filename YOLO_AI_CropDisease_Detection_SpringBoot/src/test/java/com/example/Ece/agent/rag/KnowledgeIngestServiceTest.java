package com.example.Ece.agent.rag;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeIngestServiceTest {

    private final InMemorySourceRepository sources = new InMemorySourceRepository();
    private final InMemoryChunkRepository chunks = new InMemoryChunkRepository();

    private KnowledgeIngestService service(EmbeddingClient client) {
        return new KnowledgeIngestService(new KnowledgeChunker(), client, sources, chunks);
    }

    private EmbeddingClient workingClient() {
        return new EmbeddingClient() {
            public double[] embed(String text) {
                return new double[]{1.0, 0.0};
            }
        };
    }

    private EmbeddingClient failingClient() {
        return new EmbeddingClient() {
            public double[] embed(String text) throws EmbeddingUnavailableException {
                throw new EmbeddingUnavailableException("embedding service down");
            }
        };
    }

    private KnowledgeSource source() {
        return new KnowledgeSource("SRC-TOMATO-KG", "设施番茄、黄瓜的病虫害知识图谱构建数据集", "B", 4,
                "https://example.invalid/dataset/tomato-kg", "CC-BY-4.0（示例，材料中须替换为真实许可）", "v1");
    }

    private List<IngestRecord> records() {
        Map<KnowledgeChunk.FieldType, String> fields = new LinkedHashMap<KnowledgeChunk.FieldType, String>();
        fields.put(KnowledgeChunk.FieldType.SYMPTOM, "番茄叶片出现褐色轮纹斑，湿度大时病斑扩展迅速");
        fields.put(KnowledgeChunk.FieldType.CONTROL, "可选用代森锰锌等保护性药剂，注意轮换用药");
        List<IngestRecord> records = new ArrayList<IngestRecord>();
        records.add(new IngestRecord(1L, "番茄", "早疫病", fields));
        return records;
    }

    @Test
    void rejectsSourcesWithoutProvenance() {
        assertThrows(IllegalArgumentException.class,
                () -> new KnowledgeSource("SRC-X", "", "B", 3, "http://x", "CC-BY", "v1"));
        assertThrows(IllegalArgumentException.class,
                () -> new KnowledgeSource("SRC-X", "某数据集", "B", 3, "http://x", "CC-BY", ""));
        assertThrows(IllegalArgumentException.class,
                () -> new KnowledgeSource("", "某数据集", "B", 3, "http://x", "CC-BY", "v1"));
    }

    /**
     * ingest 采用**全量替换**语义：重复 ingest 会先删旧块再重建，最终状态与首次一致。
     * 原实现按 content_hash 跳过已存在块，导致切块规则或内容调整后永远无法生效。
     */
    @Test
    void reingestReplacesChunksAndKeepsTheSameFinalState() {
        KnowledgeIngestService service = service(workingClient());
        IngestReport first = service.ingest(source(), records());
        assertEquals(2, first.getChunksNewlyWritten(), "症状与防治两个字段各成一块");
        assertEquals(0, first.getChunksRemoved(), "首次 ingest 没有旧块可删");
        assertEquals(2, first.getChunksTotal());
        assertFalse(first.isEmbeddingDegraded());

        IngestReport second = service.ingest(source(), records());
        assertEquals(2, second.getChunksRemoved(), "二次 ingest 必须先删除本来源的旧块");
        assertEquals(2, second.getChunksNewlyWritten(), "删除后按新规则重建");
        assertEquals(0, second.getChunksSkipped());
        assertEquals(first.getChunksTotal(), second.getChunksTotal(), "来源块总数不变");
    }

    @Test
    void reportsDegradedButStillStoresChunksWhenEmbeddingUnavailable() {
        KnowledgeIngestService service = service(failingClient());
        IngestReport report = service.ingest(source(), records());
        assertTrue(report.isEmbeddingDegraded(), "向量不可用必须如实标记降级");
        assertEquals(2, report.getChunksNewlyWritten(), "降级时仍应保留可关键词检索的语料");
    }

    @Test
    void countsRejectedRecordsWithReasons() {
        List<IngestRecord> bad = new ArrayList<IngestRecord>();
        bad.add(new IngestRecord(2L, "番茄", "无文本病",
                new LinkedHashMap<KnowledgeChunk.FieldType, String>()));
        IngestReport report = service(workingClient()).ingest(source(), bad);
        assertEquals(1, report.getRejected());
        assertFalse(report.getRejectedReasons().isEmpty());
        assertEquals(0, report.getChunksNewlyWritten());
    }

    @Test
    void registersSourceForTraceability() {
        service(workingClient()).ingest(source(), records());
        KnowledgeSource registered = sources.findByCode("SRC-TOMATO-KG", "v1");
        assertNotNull(registered, "来源必须登记，才能回溯每条知识的出处");
        assertEquals(4, registered.getAuthorityLevel());
    }

    /**
     * 替换一个来源不得影响其他来源的知识块。注意两个来源的**内容必须不同**：
     * 内容哈希在全局判重（同一份内容只存一次、来源以首次入库者为准），
     * 若用相同内容，第二个来源会因判重而跳过，测不到替换作用域。
     */
    @Test
    void replacingOneSourceKeepsOtherSourcesIntact() {
        KnowledgeIngestService service = service(workingClient());
        KnowledgeSource other = new KnowledgeSource("SRC-OTHER", "另一来源", "B", 3, null, null, "v1");
        List<IngestRecord> otherRecords = new ArrayList<IngestRecord>();
        Map<KnowledgeChunk.FieldType, String> otherFields = new LinkedHashMap<KnowledgeChunk.FieldType, String>();
        otherFields.put(KnowledgeChunk.FieldType.SYMPTOM, "黄瓜叶片背面出现水渍状病斑，清晨可见霉层");
        otherRecords.add(new IngestRecord("cucumber_disease", 501L, "黄瓜", "黄瓜霜霉病", otherFields));

        service.ingest(other, otherRecords);
        service.ingest(source(), records());
        assertEquals(3, chunks.loadAll().size(), "另一来源 1 块（仅症状字段）+ 本来源 2 块");

        IngestReport report = service.ingest(source(), records());

        assertEquals(2, report.getChunksRemoved(), "只应删除本来源的两块");
        assertEquals(3, chunks.loadAll().size(), "另一来源的块必须保留");
    }

    /** 知识块内容必须带上下文头（作物/病害/字段），否则病名直检会失配。 */
    @Test
    void chunkContentCarriesContextHeader() {
        service(workingClient()).ingest(source(), records());

        String content = chunks.loadAll().get(0).getContent();
        assertTrue(content.contains("早疫病"), "块内容必须含病害名：" + content);
        assertTrue(content.contains("番茄"), "块内容必须含作物名：" + content);
        assertTrue(content.contains("字段："), "块内容必须含字段标记：" + content);
    }

    /** 造 n 条各含一个症状字段的记录（文本互不相同，避免全局内容判重把它们合并）。 */
    private List<IngestRecord> manyRecords(int n) {
        List<IngestRecord> list = new ArrayList<IngestRecord>();
        for (int i = 0; i < n; i++) {
            Map<KnowledgeChunk.FieldType, String> fields = new LinkedHashMap<KnowledgeChunk.FieldType, String>();
            fields.put(KnowledgeChunk.FieldType.SYMPTOM, "第" + i + "号症状描述：叶片出现褐色轮纹斑并逐日扩展");
            list.add(new IngestRecord((long) i, "番茄", "早疫病" + i, fields));
        }
        return list;
    }

    /**
     * 块数多时必须按批向量化，而不是逐块一次 HTTP 往返。
     * 实测依据：32 条一次 79 ms vs 逐条 32 次约 1,150 ms。
     */
    @Test
    void embedsInBatchesInsteadOfOneRequestPerChunk() {
        CountingEmbeddingClient client = new CountingEmbeddingClient(true, true);
        IngestReport report = service(client).ingest(source(), manyRecords(40));

        assertEquals(40, report.getChunksNewlyWritten());
        assertEquals(0, client.singleCalls, "整批可用时不应有任何逐条请求");
        assertEquals(2, client.batchCalls, "40 块 = 32 + 8 两批");
        assertEquals(Arrays.asList(Integer.valueOf(32), Integer.valueOf(8)), client.batchSizes);
        assertFalse(report.isEmbeddingDegraded());
    }

    /**
     * 整批失败退回逐条：**一条坏文本不该让同批另外几十条一起丢向量**。
     * 逐条成功即不标记降级——向量确实都拿到了。
     */
    @Test
    void fallsBackToPerChunkWhenBatchRequestFails() {
        CountingEmbeddingClient client = new CountingEmbeddingClient(false, true);
        IngestReport report = service(client).ingest(source(), manyRecords(40));

        assertEquals(40, report.getChunksNewlyWritten());
        assertEquals(40, client.singleCalls, "批量不可用时必须逐条补齐");
        assertFalse(report.isEmbeddingDegraded(), "逐条都成功，向量齐全，不应报降级");
        for (double[] vector : chunks.loadEmbeddings()) {
            assertNotNull(vector, "逐条成功时不应留下空向量");
        }
    }

    /**
     * 服务真的挂了要**早停**：否则一个没起来的 Flask 会按"剩余块数 × 3 s 读超时"把 ingest 拖成几十分钟。
     * 连续 2 条失败即认定不可用，剩余块直接置空并如实标记降级。
     */
    @Test
    void stopsRetryingOnceEmbeddingServiceLooksDown() {
        CountingEmbeddingClient client = new CountingEmbeddingClient(false, false);
        IngestReport report = service(client).ingest(source(), manyRecords(40));

        assertEquals(40, report.getChunksNewlyWritten(), "降级也要保住可关键词检索的语料");
        assertTrue(report.isEmbeddingDegraded(), "向量缺失必须如实标记");
        assertEquals(1, client.batchCalls, "整批失败一次");
        assertEquals(2, client.singleCalls, "逐条重试连续 2 条失败即停，而不是试满 40 次");
    }

    /** 可计数的假客户端：分别控制"整批"与"逐条"是否成功，并记录调用次数与批大小。 */
    private static final class CountingEmbeddingClient implements EmbeddingClient {

        private final boolean batchWorks;
        private final boolean singleWorks;
        private int batchCalls;
        private int singleCalls;
        private final List<Integer> batchSizes = new ArrayList<Integer>();

        CountingEmbeddingClient(boolean batchWorks, boolean singleWorks) {
            this.batchWorks = batchWorks;
            this.singleWorks = singleWorks;
        }

        public double[] embed(String text) throws EmbeddingUnavailableException {
            singleCalls++;
            if (!singleWorks) {
                throw new EmbeddingUnavailableException("embedding service down");
            }
            return new double[]{1.0, 0.0};
        }

        public List<double[]> embedBatch(List<String> texts) throws EmbeddingUnavailableException {
            batchCalls++;
            batchSizes.add(Integer.valueOf(texts.size()));
            if (!batchWorks) {
                throw new EmbeddingUnavailableException("batch endpoint failed");
            }
            List<double[]> vectors = new ArrayList<double[]>();
            for (int i = 0; i < texts.size(); i++) {
                vectors.add(new double[]{1.0, 0.0});
            }
            return vectors;
        }
    }

    /** 内存实现。 */
    private static final class InMemorySourceRepository implements KnowledgeSourceRepository {
        private final Map<String, KnowledgeSource> store = new LinkedHashMap<String, KnowledgeSource>();

        public void save(KnowledgeSource source) {
            store.put(source.getSourceCode() + "|" + source.getVersion(), source);
        }

        public KnowledgeSource findByCode(String sourceCode, String version) {
            return store.get(sourceCode + "|" + version);
        }

        public List<KnowledgeSource> findAll() {
            return new ArrayList<KnowledgeSource>(store.values());
        }
    }

    /** 内存实现：知识块写入（全局判重 + 按来源记账，忠实模拟 SQL 的 join 删除）。 */
    private static final class InMemoryChunkRepository implements KnowledgeChunkRepository {
        private final Set<String> hashes = new LinkedHashSet<String>();
        private final List<KnowledgeChunk> stored = new ArrayList<KnowledgeChunk>();
        private final List<double[]> vectors = new ArrayList<double[]>();
        private final List<String> owners = new ArrayList<String>();

        public Set<String> existingContentHashes() {
            return new LinkedHashSet<String>(hashes);
        }

        public int deleteBySourceCode(String sourceCode) {
            int removed = 0;
            for (int i = stored.size() - 1; i >= 0; i--) {
                if (sourceCode.equals(owners.get(i))) {
                    hashes.remove(stored.get(i).getContentHash());
                    stored.remove(i);
                    vectors.remove(i);
                    owners.remove(i);
                    removed++;
                }
            }
            return removed;
        }

        public int saveAll(String sourceCode, int authorityLevel, List<KnowledgeChunk> chunkList,
                           List<double[]> embeddings, String embeddingModel) {
            int written = 0;
            for (int i = 0; i < chunkList.size(); i++) {
                KnowledgeChunk chunk = chunkList.get(i);
                if (hashes.add(chunk.getContentHash())) {
                    stored.add(chunk);
                    vectors.add(embeddings == null || i >= embeddings.size() ? null : embeddings.get(i));
                    owners.add(sourceCode);
                    written++;
                }
            }
            return written;
        }

        public List<KnowledgeChunk> loadAll() {
            return new ArrayList<KnowledgeChunk>(stored);
        }

        public List<double[]> loadEmbeddings() {
            return new ArrayList<double[]>(vectors);
        }
    }
}
