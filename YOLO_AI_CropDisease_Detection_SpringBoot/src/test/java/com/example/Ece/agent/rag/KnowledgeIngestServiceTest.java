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

    @Test
    void ingestIsIdempotentByContentHash() {
        KnowledgeIngestService service = service(workingClient());
        IngestReport first = service.ingest(source(), records());
        assertEquals(2, first.getChunksNewlyWritten(), "症状与防治两个字段各成一块");
        assertEquals(2, first.getChunksTotal());
        assertFalse(first.isEmbeddingDegraded());

        IngestReport second = service.ingest(source(), records());
        assertEquals(0, second.getChunksNewlyWritten(), "重复 ingest 不应写入新块");
        assertEquals(2, second.getChunksSkipped(), "重复块应按 content_hash 判重跳过");
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

    /** 内存实现：来源登记。 */
    private static final class InMemorySourceRepository implements KnowledgeSourceRepository {
        private final Map<String, KnowledgeSource> store = new LinkedHashMap<String, KnowledgeSource>();

        public void save(KnowledgeSource source) {
            store.put(source.getSourceCode() + "|" + source.getVersion(), source);
        }

        public KnowledgeSource findByCode(String sourceCode, String version) {
            return store.get(sourceCode + "|" + version);
        }
    }

    /** 内存实现：知识块写入（全局判重）。 */
    private static final class InMemoryChunkRepository implements KnowledgeChunkRepository {
        private final Set<String> hashes = new LinkedHashSet<String>();
        private final List<KnowledgeChunk> stored = new ArrayList<KnowledgeChunk>();

        public Set<String> existingContentHashes() {
            return new LinkedHashSet<String>(hashes);
        }

        public int saveAll(String sourceCode, int authorityLevel, List<KnowledgeChunk> chunkList,
                           List<double[]> embeddings, String embeddingModel) {
            int written = 0;
            for (KnowledgeChunk chunk : chunkList) {
                if (hashes.add(chunk.getContentHash())) {
                    stored.add(chunk);
                    written++;
                }
            }
            return written;
        }
    }
}
