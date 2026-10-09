package com.example.Ece.agent.rag;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 启动装配必须**按来源分组一次性灌**的回归测试。
 *
 * <p>锁的是一个不出声的 bug：{@code KnowledgeIngestService.ingest} 按来源代码**全量替换**
 * （内部先 {@code deleteBySourceCode} 再写）。首版装配逐条调用，于是灌第二条会删掉第一条，
 * 15 条标准摘要**只活下来 1 条**，而且日志、返回值、测试全都不报错——
 * 是事后查库（{@code field_type} 里只剩 WATER_FERT/ENVIRONMENT 两类）才发现的。</p>
 *
 * <p>因此这里断言的不是"灌过了"，而是"**同一次调用里带齐了该来源的全部记录**"。</p>
 */
class KnowledgeBootstrapGroupingTest {

    private final KnowledgeChunkRepository chunkRepository = mock(KnowledgeChunkRepository.class);
    private final KnowledgeIngestService ingestService = mock(KnowledgeIngestService.class);
    private final LegacyDiseaseKnowledgeReader legacyReader = mock(LegacyDiseaseKnowledgeReader.class);
    private final CuratedCropKnowledgeReader curatedReader = mock(CuratedCropKnowledgeReader.class);
    private final KnowledgeSourceRepository sourceRepository = mock(KnowledgeSourceRepository.class);
    private final KnowledgeIndexService indexService = mock(KnowledgeIndexService.class);
    private final KnowledgeChunker chunker = new KnowledgeChunker();
    private final CitedKnowledgeReader standardReader = new CitedKnowledgeReader();

    private KnowledgeBootstrap bootstrap() {
        when(curatedReader.readAll()).thenReturn(Collections.<KnowledgeSourceEntry>emptyList());
        when(ingestService.ingest(any(KnowledgeSource.class), any(List.class)))
                .thenAnswer(invocation -> new IngestReport(
                        invocation.getArgument(0, KnowledgeSource.class).getSourceCode()));
        return new KnowledgeBootstrap(chunkRepository, ingestService, legacyReader, curatedReader,
                standardReader, sourceRepository, chunker, indexService);
    }

    /** 库非空时不得重灌历史病害库（那是全量替换，代价最大且没必要）。 */
    @Test
    void doesNotReingestLegacyLibraryWhenChunksExist() {
        when(chunkRepository.loadAll()).thenReturn(Collections.singletonList(chunkOf("legacy-disease-db")));
        when(sourceRepository.findByCode(anyString(), anyString())).thenReturn(null);

        bootstrap().run(null);

        // 只断言"没去读历史库"——标准摘要在这个场景下**应该**被灌（未登记），
        // 因此不能用"ingest 一次都没调"来断言，那是把两件事混成一条。
        verify(legacyReader, never()).readAll();
    }

    /** 核心断言：每个标准各只灌一次，且这一次带齐了该来源的全部条目。 */
    @Test
    @SuppressWarnings("unchecked")
    void ingestsEachStandardOnceWithAllItsRecords() {
        when(chunkRepository.loadAll()).thenReturn(Collections.singletonList(chunkOf("legacy-disease-db")));
        when(sourceRepository.findByCode(anyString(), anyString())).thenReturn(null);

        ArgumentCaptor<KnowledgeSource> sources = ArgumentCaptor.forClass(KnowledgeSource.class);
        ArgumentCaptor<List> records = ArgumentCaptor.forClass(List.class);
        bootstrap().run(null);

        int standardCount = expectedSourceCount();
        verify(ingestService, times(standardCount)).ingest(sources.capture(), records.capture());

        // 期望的条目数从清单本身推出来，而不是写死——写死的话每加一份标准都要改测试，
        // 而这条测试真正要守的是"分组完整"，不是某个具体数字。
        Map<String, Integer> expected = new HashMap<String, Integer>();
        Map<String, Integer> actual = new HashMap<String, Integer>();
        for (int i = 0; i < sources.getAllValues().size(); i++) {
            actual.put(sources.getAllValues().get(i).getSourceCode(),
                    Integer.valueOf(records.getAllValues().get(i).size()));
        }
        for (KnowledgeSourceEntry entry : standardReader.readAll()) {
            String code = entry.getSource().getSourceCode();
            Integer current = expected.get(code);
            expected.put(code, Integer.valueOf(current == null ? 1 : current.intValue() + 1));
        }
        assertEquals(expected, actual,
                "每个来源必须一次灌完它的全部条目；分批灌只会留下最后一条（实测曾 15 条只活 1 条）");
    }

    /** 已登记且块数吻合的来源不应重灌——否则每次启动都会 delete + insert 一遍全库。 */
    @Test
    @SuppressWarnings("unchecked")
    void skipsStandardWhoseChunkCountAlreadyMatches() {
        List<KnowledgeChunk> existing = new ArrayList<KnowledgeChunk>();
        for (int i = 0; i < expectedChunksOf("std-nyt5449-2026"); i++) {
            existing.add(chunkOf("std-nyt5449-2026"));
        }
        when(chunkRepository.loadAll()).thenReturn(existing);
        when(sourceRepository.findByCode("std-nyt5449-2026", "2026"))
                .thenReturn(new KnowledgeSource("std-nyt5449-2026", "已登记", "A", 1,
                        "https://example.org/x", "lic", "2026"));
        when(sourceRepository.findByCode(anyString(), anyString())).thenAnswer(invocation ->
                "std-nyt5449-2026".equals(invocation.getArgument(0))
                        ? new KnowledgeSource("std-nyt5449-2026", "已登记", "A", 1,
                                "https://example.org/x", "lic", "2026")
                        : null);

        ArgumentCaptor<KnowledgeSource> sources = ArgumentCaptor.forClass(KnowledgeSource.class);
        bootstrap().run(null);
        // 先 verify 才会真正填充 captor——不 verify 就读 getAllValues() 只会拿到空列表，
        // 断言会以"什么都没灌"的方式通过或失败，与真实行为无关。
        verify(ingestService, atLeastOnce()).ingest(sources.capture(), any(List.class));

        List<String> ingested = new ArrayList<String>();
        for (KnowledgeSource source : sources.getAllValues()) {
            ingested.add(source.getSourceCode());
        }
        assertTrue(!ingested.contains("std-nyt5449-2026"),
                "块数吻合的来源应被跳过，实测仍被重灌：" + ingested);
        assertEquals(expectedSourceCount() - 1, ingested.size(),
                "除已就绪的那一份外，其余来源都应待灌：" + ingested);
    }

    private int expectedSourceCount() {
        java.util.Set<String> codes = new HashSet<String>();
        for (KnowledgeSourceEntry entry : standardReader.readAll()) {
            codes.add(entry.getSource().getSourceCode());
        }
        return codes.size();
    }

    private int expectedChunksOf(String sourceCode) {
        int total = 0;
        for (KnowledgeSourceEntry entry : standardReader.readAll()) {
            if (sourceCode.equals(entry.getSource().getSourceCode())) {
                total += chunker.chunk(entry.getRecord().getSourceTable(), entry.getRecord().getSourceId(),
                        entry.getRecord().getCropType(), entry.getRecord().getDiseaseName(),
                        entry.getRecord().getFields()).size();
            }
        }
        assertTrue(total > 0, "测试前提：该来源应有可切出的块");
        return total;
    }

    private KnowledgeChunk chunkOf(String sourceCode) {
        return new KnowledgeChunk("disease", 1L, "番茄", "早疫病", KnowledgeChunk.FieldType.SYMPTOM,
                0, 0, "作物：番茄；病害：早疫病；字段：症状。叶片出现褐色轮纹斑。",
                KnowledgeChunker.sha256("chunk-" + sourceCode), sourceCode, "样本来源", "E", null, "v1");
    }
}
