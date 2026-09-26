package com.example.Ece.agent.parameter;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 登记服务的测试：刷新要幂等、统计要如实反映缺口。 */
class ParameterSourceServiceTest {

    /**
     * 构造一条登记。
     *
     * <p>注意 {@code VERIFIED} 必须带 URL：解析规则里 URL 是最硬的判据，
     * 没有 URL 的条目一律保守判为未核实。初版这里漏传 URL，
     * 于是"已核实"的测试数据被解析成未核实——**恰好证明了这个回退在生效**。</p>
     */
    private ParameterSource source(String code, String unit, ParameterProvenance.Status status) {
        String url = status == ParameterProvenance.Status.VERIFIED ? "https://example.org/spec" : null;
        return new ParameterSource(code, "参数" + code, "1.0", unit,
                ParameterProvenance.decorate(status, "出处说明"), url,
                ParameterRegistry.REGISTRY_VERSION);
    }

    @Test
    void refreshRebuildsTheSnapshotInsteadOfOnlyUpserting() {
        ParameterSourceRepository repository = mock(ParameterSourceRepository.class);
        ParameterRegistry registry = new ParameterRegistry();
        ParameterSourceService service = new ParameterSourceService(registry, repository);

        int expected = registry.enumerate().size();
        int actual = service.refresh();

        assertEquals(expected, actual, "刷新条数应等于枚举条数");
        ArgumentCaptor<ParameterSource> captor = ArgumentCaptor.forClass(ParameterSource.class);
        verify(repository, times(expected)).upsert(captor.capture());
        for (ParameterSource captured : captor.getAllValues()) {
            assertEquals(ParameterRegistry.REGISTRY_VERSION, captured.getVersion(),
                    "登记版本必须统一，否则引用登记结果时无从复现");
        }
        // 必须**先清空本版本**：只 upsert 会留下陈旧行（旧编码格式、上一次失败刷新的半成品），
        // 且因版本号未变既不被覆盖也不被 deleteOtherVersions 清理。实测出现过总数比登记数还多。
        verify(repository).deleteByVersion(eq(ParameterRegistry.REGISTRY_VERSION));
        verify(repository).deleteOtherVersions(eq(ParameterRegistry.REGISTRY_VERSION));
    }

    @Test
    void summaryCountsStatusesAndFlagsMissingUnits() {
        ParameterSourceRepository repository = mock(ParameterSourceRepository.class);
        when(repository.listByVersion(ParameterRegistry.REGISTRY_VERSION)).thenReturn(new ArrayList<ParameterSource>(Arrays.asList(
                source("A", "kg", ParameterProvenance.Status.VERIFIED),
                source("B", null, ParameterProvenance.Status.UNVERIFIED_LITERATURE),
                source("C", null, ParameterProvenance.Status.PLACEHOLDER))));
        ParameterSourceService service = new ParameterSourceService(new ParameterRegistry(), repository);

        Map<String, Object> summary = service.summary();

        assertEquals(Integer.valueOf(3), summary.get("total"));
        assertEquals(Integer.valueOf(1), summary.get("citable"), "只有带 URL 的那条可引用");
        assertEquals(Integer.valueOf(2), summary.get("unitMissing"), "两条没单位，应如实计数");
        @SuppressWarnings("unchecked")
        Map<String, Object> byStatus = (Map<String, Object>) summary.get("byStatus");
        assertEquals(Integer.valueOf(1), byStatus.get("VERIFIED"));
        assertEquals(Integer.valueOf(1), byStatus.get("UNVERIFIED_LITERATURE"));
        assertEquals(Integer.valueOf(1), byStatus.get("PLACEHOLDER"));
    }

    @Test
    void listWithoutStatusReturnsEverythingFromTheCurrentVersion() {
        ParameterSourceRepository repository = mock(ParameterSourceRepository.class);
        when(repository.listByVersion(any(String.class))).thenReturn(new ArrayList<ParameterSource>(Arrays.asList(
                source("A", "kg", ParameterProvenance.Status.VERIFIED),
                source("B", null, ParameterProvenance.Status.PLACEHOLDER))));
        ParameterSourceService service = new ParameterSourceService(new ParameterRegistry(), repository);

        assertEquals(2, service.list(null).size());
        assertEquals(1, service.list(ParameterProvenance.Status.PLACEHOLDER).size());
        assertEquals(0, service.list(ParameterProvenance.Status.UNVERIFIED_LITERATURE).size());
    }

    @Test
    void statusParsingUsesUrlAsTheHardestEvidence() {
        // 有 URL 一律可引用，即使名字里没带任何标签
        assertEquals(ParameterProvenance.Status.VERIFIED,
                ParameterProvenance.parse("FAO-56", "https://www.fao.org/x"));
        // 没 URL 且没前缀 → 保守判为未核实，宁可低估可引用性
        assertEquals(ParameterProvenance.Status.UNVERIFIED_LITERATURE,
                ParameterProvenance.parse("某文献族", null));
        assertEquals(ParameterProvenance.Status.PLACEHOLDER,
                ParameterProvenance.parse(ParameterProvenance.PLACEHOLDER_PREFIX + "当地水价待填", null));
    }

    @Test
    void summaryAlwaysDisclosesWhatIsNotCovered() {
        ParameterSourceRepository repository = mock(ParameterSourceRepository.class);
        when(repository.listByVersion(any(String.class))).thenReturn(new ArrayList<ParameterSource>());
        ParameterSourceService service = new ParameterSourceService(new ParameterRegistry(), repository);

        Map<String, Object> summary = service.summary();

        assertTrue(summary.containsKey("uncoveredNote"), "必须披露未覆盖的内联字面量");
        assertTrue(!((List<?>) summary.get("uncoveredNote")).isEmpty());
    }
}
