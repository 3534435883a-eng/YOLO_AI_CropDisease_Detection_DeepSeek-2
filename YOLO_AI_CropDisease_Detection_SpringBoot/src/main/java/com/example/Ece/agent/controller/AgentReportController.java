package com.example.Ece.agent.controller;

import com.example.Ece.agent.profile.HortiM3Profile;

import com.example.Ece.agent.report.ProductionReportService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 生产规划报告导出。
 *
 * <p><b>这里刻意不套 {@code Result} 信封</b>：导出的是文档本身，包一层 JSON 会让调用方
 * 无法直接落盘或用 Markdown 阅读器打开。这是本项目第二处偏离统一信封的接口
 * （另一处是 {@code /ai/agent/history/export}），均已在文档中说明。</p>
 *
 * <p>同种子同参数必然逐值复现，因此报告可被复核——这是它与其他"生成式报告"的关键区别。</p>
 */
@RestController
@RequestMapping("/ai/agent/report")
public class AgentReportController {

    private static final long DEFAULT_SEED = 20250419L;

    private final ProductionReportService reportService;

    public AgentReportController(ProductionReportService reportService) {
        this.reportService = reportService;
    }

    /**
     * 生成并导出 Markdown 报告。
     *
     * @param seed    天气相位种子，缺省 20260921
     * @param days    推演天数，缺省 56（与评测平台默认一致）
     * @param batchId 批次号，可选
     */
    @GetMapping(produces = "text/markdown;charset=UTF-8")
    public String report(@RequestParam(value = "seed", required = false) Long seed,
                         @RequestParam(value = "days", required = false) Integer days,
                         @RequestParam(value = "batchId", required = false) String batchId) {
        long effectiveSeed = seed == null ? DEFAULT_SEED : seed.longValue();
        int effectiveDays = days == null || days.intValue() <= 0 ? HortiM3Profile.DEFAULT_DAYS : days.intValue();
        return reportService.renderMarkdown(batchId, effectiveSeed, effectiveDays);
    }
}
