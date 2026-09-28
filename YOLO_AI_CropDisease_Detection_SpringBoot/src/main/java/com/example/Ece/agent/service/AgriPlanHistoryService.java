package com.example.Ece.agent.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.example.Ece.agent.plan.AgriPlanBaseline;
import com.example.Ece.agent.plan.AgriSituationInput;
import com.example.Ece.agent.plan.DeductionResult;
import com.example.Ece.agent.repository.AgriPlanRunRepository;
import com.example.Ece.agent.repository.AgriPlanRunRepository.PlanRunRow;
import com.example.Ece.agent.report.ProductionReportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 推演记录的半永久化：写入 {@code agent_plan_run}，数据库不可用时退回本地 JSONL，
 * **任何情况下都不向调用方抛异常**。
 *
 * <p>与 {@code AgentChatHistoryService} 同一条理由：落库是审计所需的旁路，不是推演本身。
 * 让 MySQL 抖动导致用户拿不到一份已经生成好的推演，是把旁路的失败升级成主流程的失败。</p>
 */
@Service
public class AgriPlanHistoryService {

    private static final Logger log = LoggerFactory.getLogger(AgriPlanHistoryService.class);

    public static final String DEFAULT_FALLBACK_PATH = "logs/agri-plan-runs-fallback.jsonl";
    private static final int MAX_FIELD_CHARS = 20000;

    private final AgriPlanRunRepository repository;
    private final ProductionReportService reportService;
    private final Path fallbackPath;
    private final Object fallbackLock = new Object();

    @Autowired
    public AgriPlanHistoryService(AgriPlanRunRepository repository, ProductionReportService reportService,
                                  @Value("${agent.plan.fallback-path:" + DEFAULT_FALLBACK_PATH + "}")
                                  String fallbackPath) {
        this(repository, reportService, Paths.get(fallbackPath));
    }

    /** 不做报告渲染的构造（单元测试用）。 */
    public AgriPlanHistoryService(AgriPlanRunRepository repository, Path fallbackPath) {
        this(repository, null, fallbackPath);
    }

    public AgriPlanHistoryService(AgriPlanRunRepository repository, ProductionReportService reportService,
                                  Path fallbackPath) {
        this.repository = repository;
        this.reportService = reportService;
        this.fallbackPath = fallbackPath;
    }

    /**
     * 记录一次推演的终态。**永不抛异常**——失败只记日志并落兜底文件。
     *
     * @return 落库得到的记录编号；数据库与兜底文件都失败时返回 {@code null}
     */
    public Long record(AgriSituationInput situation, String question, DeductionResult result) {
        if (result == null) {
            return null;
        }
        PlanRunRow row = toRow(situation, question, result);
        try {
            return Long.valueOf(repository.insert(row));
        } catch (RuntimeException error) {
            log.warn("推演记录写入数据库失败，改用本地兜底文件 {}：{}", fallbackPath, error.toString());
            appendFallback(row);
            return null;
        }
    }

    public List<PlanRunRow> listRecent(int limit) {
        return repository.listRecent(limit);
    }

    public PlanRunRow findById(long id) {
        return repository.findById(id);
    }

    /**
     * 导出 Markdown：**推演正文 + 一节内嵌的可复算基线**。
     *
     * <p>两份内容来源完全不同，因此在同一份文档里也**必须分开标注**：推演正文是模型用自身知识
     * 生成的、无从核对；基线那一节复用 {@code ProductionReportService.renderMarkdown}
     * （同种子同天数可逐值复现）。把它们混成一段，读者就无法判断哪句该去核对。
     * 这也正是这个功能的意义——同一份文档里既有"能发挥的部分"，也有"能复算的部分"。</p>
     *
     * @return Markdown 文档；记录不存在时返回 {@code null}
     */
    public String exportMarkdown(long id) {
        PlanRunRow row = repository.findById(id);
        if (row == null) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        builder.append("# 农事规划推演报告\n\n");
        builder.append("> 记录编号 ").append(row.id).append("　生成时间 ").append(row.createdAt).append('\n');
        builder.append("> 推演方式：").append("STREAM".equals(row.streamMode) ? "流式生成" : "降级整段生成");
        if (row.fallbackReason != null) {
            builder.append("（").append(row.fallbackReason).append("）");
        }
        builder.append("　耗时 ").append(row.elapsedMs / 1000.0).append(" 秒\n");
        if (row.bannerInjected) {
            builder.append("> 注：首行推演声明由系统补充（模型未按格式输出）\n");
        }
        builder.append('\n');

        builder.append("## 一、本次提供的农情\n\n");
        builder.append(row.situationJson == null || row.situationJson.trim().isEmpty()
                ? "（未提供结构化农情）" : "```json\n" + row.situationJson + "\n```");
        builder.append("\n\n");
        if (row.question != null && !row.question.trim().isEmpty()) {
            builder.append("**用户诉求**：").append(row.question).append("\n\n");
        }
        if (row.missingFields != null && !row.missingFields.trim().isEmpty()) {
            builder.append("**本次未提供的字段**：").append(row.missingFields).append("\n\n");
        }

        builder.append("## 二、模型推演方案\n\n");
        builder.append(stripBanner(row.answerMarkdown)).append("\n\n");

        if (reportService == null || row.baselineBatchId == null) {
            builder.append("## 三、机理模型参考基线\n\n本记录没有可用的参考基线。\n");
            return builder.toString();
        }
        builder.append("---\n\n");
        builder.append("# 附录：机理模型参考基线（可复算）\n\n");
        builder.append("> **以下内容与本报告正文来源不同。** 正文是模型用自身知识做的推演；"
                + "本附录由机理模型在**固定标准情景**下跑出，"
                + "**不采用上面那份农情输入**，同种子同天数可逐值复现。\n");
        builder.append("> 复算标识：批次 ").append(row.baselineBatchId)
                .append("，种子 ").append(row.seed).append("，天数 ").append(row.days).append("\n\n");
        try {
            builder.append(reportService.renderMarkdown(row.baselineBatchId, row.seed, row.days));
        } catch (RuntimeException error) {
            log.warn("报告渲染失败，导出中略去附录：{}", error.toString());
            builder.append("（附录渲染失败，请通过 GET /ai/agent/report?seed=")
                    .append(row.seed).append("&days=").append(row.days).append(" 单独获取）\n");
        }
        return builder.toString();
    }

    /** 正文首行已有推演声明，导出时顶层标题下再放一次会重复，这里去掉它。 */
    private String stripBanner(String markdown) {
        if (markdown == null) {
            return "";
        }
        return markdown.replace(com.example.Ece.agent.plan.DeductionPrompts.SIMULATION_BANNER, "").trim();
    }

    private PlanRunRow toRow(AgriSituationInput situation, String question, DeductionResult result) {
        PlanRunRow row = new PlanRunRow();
        AgriPlanBaseline.Baseline baseline = result.getBaseline();
        row.seed = baseline == null ? 0L : baseline.getSeed();
        row.days = baseline == null ? 0 : baseline.getDays();
        row.question = truncate(question);
        row.situationJson = situation == null || situation.isEmpty()
                ? null : JSON.toJSONString(situation.asMap());
        row.answerMarkdown = truncate(result.getMarkdown());
        row.structuredJson = result.getStructuredJson();
        row.bannerInjected = result.isBannerInjected();
        row.streamMode = result.getStreamMode() == null ? "STREAM" : result.getStreamMode().name();
        row.fallbackReason = truncate(result.getFallbackReason(), 250);
        row.baselineBatchId = baseline == null ? null : baseline.getBatchId();
        row.baselineJson = baselinePayload(baseline);
        row.sections = truncate(String.join(",", result.getSections()), 500);
        row.missingFields = truncate(String.join(",", result.getMissingFields()), 500);
        row.elapsedMs = result.getElapsedMillis();
        return row;
    }

    private String baselinePayload(AgriPlanBaseline.Baseline baseline) {
        if (baseline == null) {
            return null;
        }
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("batchId", baseline.getBatchId());
        payload.put("seed", Long.valueOf(baseline.getSeed()));
        payload.put("days", Integer.valueOf(baseline.getDays()));
        payload.put("strategyLabel", baseline.getStrategyLabel());
        payload.put("marketableYieldKg", Double.valueOf(baseline.getMarketableYieldKg()));
        payload.put("profitYuan", Double.valueOf(baseline.getProfitYuan()));
        payload.put("scopeNote", "固定标准情景基线，不采用用户提供的农情输入");
        payload.put("windowNote", baseline.getWindowNote());
        return JSON.toJSONString(payload, SerializerFeature.WriteMapNullValue);
    }

    private boolean appendFallback(PlanRunRow row) {
        Map<String, Object> record = new LinkedHashMap<String, Object>();
        record.put("seed", Long.valueOf(row.seed));
        record.put("days", Integer.valueOf(row.days));
        record.put("question", row.question);
        record.put("situation", row.situationJson);
        record.put("answerMarkdown", row.answerMarkdown);
        record.put("streamMode", row.streamMode);
        record.put("bannerInjected", Boolean.valueOf(row.bannerInjected));
        record.put("baselineBatchId", row.baselineBatchId);
        record.put("fallback", Boolean.TRUE);
        String line = JSON.toJSONString(record, SerializerFeature.WriteMapNullValue) + "\n";
        synchronized (fallbackLock) {
            try {
                Path parent = fallbackPath.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.write(fallbackPath, line.getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                return true;
            } catch (IOException error) {
                log.error("推演记录兜底写入同样失败，本次记录丢失：{}", error.toString());
                return false;
            }
        }
    }

    private String truncate(String value) {
        return truncate(value, MAX_FIELD_CHARS);
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max) + "…(截断)";
    }
}
