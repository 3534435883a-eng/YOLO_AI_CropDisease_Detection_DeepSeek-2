package com.example.Ece.agent.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.example.Ece.agent.plan.AgriPlanBaseline;
import com.example.Ece.agent.plan.AgriSituationInput;
import com.example.Ece.agent.plan.DeductionResult;
import com.example.Ece.agent.repository.AgriPlanRunRepository;
import com.example.Ece.agent.repository.AgriPlanRunRepository.PlanRunRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 推演记录持久化的兜底测试。
 *
 * <p>盯的是"旁路失败不该影响主流程"这条约定：数据库写不进去时，用户仍应拿到推演结果，
 * 记录落到本地文件而不是丢掉。</p>
 */
class AgriPlanHistoryServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void writesToDatabaseAndReturnsId() {
        AgriPlanRunRepository repository = mock(AgriPlanRunRepository.class);
        when(repository.insert(any(PlanRunRow.class))).thenReturn(Long.valueOf(42L));
        AgriPlanHistoryService service = new AgriPlanHistoryService(repository, null);

        Long id = service.record(situation(), "问题", result());

        assertEquals(Long.valueOf(42L), id);
    }

    @Test
    void fallsBackToFileInsteadOfThrowingWhenDatabaseIsUnavailable() throws IOException {
        AgriPlanRunRepository repository = mock(AgriPlanRunRepository.class);
        when(repository.insert(any(PlanRunRow.class)))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("库挂了"));
        Path fallback = tempDir.resolve("nested/agri-plan-runs-fallback.jsonl");
        AgriPlanHistoryService service = new AgriPlanHistoryService(repository, fallback);

        // 不能抛——那会把一次已经成功的推演变成失败。
        Long id = service.record(situation(), "问题", result());

        assertNull(id, "落库失败时应返回 null，而不是假装成功");
        assertTrue(Files.exists(fallback), "落库失败时必须写本地兜底文件");
        String line = new String(Files.readAllBytes(fallback), StandardCharsets.UTF_8).trim();
        JSONObject record = JSON.parseObject(line);
        assertTrue(record.getBooleanValue("fallback"));
        assertNotNull(record.getString("answerMarkdown"));
    }

    @Test
    void exportWithoutReportServiceStillProducesTheDeductionBody() {
        AgriPlanRunRepository repository = mock(AgriPlanRunRepository.class);
        when(repository.findById(7L)).thenReturn(row());
        // reportService 为 null：附录渲染不可用，但正文仍应完整导出。
        AgriPlanHistoryService service = new AgriPlanHistoryService(repository, null);

        String markdown = service.exportMarkdown(7L);

        assertNotNull(markdown);
        assertTrue(markdown.contains("模型推演方案"));
        assertTrue(markdown.contains("控湿为先"));
        assertTrue(markdown.contains("本记录没有可用的参考基线"));
        // 正文首行的推演声明在导出时会被顶层说明取代，不该重复出现两遍。
        assertEquals(0, countOccurrences(markdown, "非实测、非知识库依据"), "声明不应重复");
    }

    @Test
    void exportOfMissingRecordReturnsNull() {
        AgriPlanRunRepository repository = mock(AgriPlanRunRepository.class);
        when(repository.findById(404L)).thenReturn(null);

        assertNull(new AgriPlanHistoryService(repository, null).exportMarkdown(404L));
    }

    @Test
    void recordsBannerInjectionAndFallbackSoTheyCanBeAudited() {
        AgriPlanRunRepository repository = mock(AgriPlanRunRepository.class);
        final PlanRunRow[] captured = new PlanRunRow[1];
        when(repository.insert(any(PlanRunRow.class))).thenAnswer(invocation -> {
            captured[0] = invocation.getArgument(0);
            return Long.valueOf(1L);
        });

        new AgriPlanHistoryService(repository, null).record(situation(), "问题",
                new DeductionResult("正文", true, null, Arrays.asList("一、农情判读"),
                        Arrays.asList("品种"), baseline(), DeductionResult.StreamMode.FALLBACK,
                        "流式通道未就绪，已退回整段返回", 1234L));

        // 这两项是"这次推演到底可不可信"的判据，必须落库。
        assertTrue(captured[0].bannerInjected, "声明是服务端补的，这件事要留痕");
        assertEquals("FALLBACK", captured[0].streamMode);
        assertNotNull(captured[0].fallbackReason);
        assertEquals("品种", captured[0].missingFields);
        assertNotNull(captured[0].situationJson);
    }

    /* ------------------------------ 辅助 ------------------------------ */

    private AgriSituationInput situation() {
        AgriSituationInput input = new AgriSituationInput();
        input.put("crop", "番茄");
        input.put("temperatureC", Integer.valueOf(27));
        return input;
    }

    /**
     * 用 mock 而不是为测试把 {@code Baseline.unavailable} 放宽成 public。
     * 这些用例只关心"记录里存了什么"，不关心基线数值。
     */
    private AgriPlanBaseline.Baseline baseline() {
        AgriPlanBaseline.Baseline stub = mock(AgriPlanBaseline.Baseline.class);
        when(stub.isAvailable()).thenReturn(Boolean.TRUE);
        when(stub.getSeed()).thenReturn(Long.valueOf(20260928L));
        when(stub.getDays()).thenReturn(Integer.valueOf(60));
        return stub;
    }

    private PlanRunRow row() {
        PlanRunRow row = new PlanRunRow();
        row.id = Long.valueOf(7L);
        row.seed = 20260928L;
        row.days = 60;
        row.question = "问题";
        row.situationJson = "{\"crop\":\"番茄\"}";
        row.answerMarkdown = "## 一、农情判读\n\n控湿为先。";
        row.streamMode = "STREAM";
        row.sections = "一、农情判读";
        row.missingFields = "品种";
        row.elapsedMs = 1000L;
        row.createdAt = "2026-09-28 12:00:00";
        // baselineBatchId 留 null：这条用例专门验证"没有附录也能导出"
        return row;
    }

    private DeductionResult result() {
        return new DeductionResult("## 一、农情判读\n\n控湿为先。", false, "{}",
                new ArrayList<String>(), new ArrayList<String>(), baseline(),
                DeductionResult.StreamMode.STREAM, null, 1000L);
    }

    private int countOccurrences(String text, String needle) {
        int count = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            count++;
            index = text.indexOf(needle, index + needle.length());
        }
        return count;
    }
}
