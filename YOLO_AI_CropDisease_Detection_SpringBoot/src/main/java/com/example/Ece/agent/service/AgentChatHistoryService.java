package com.example.Ece.agent.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.example.Ece.agent.orchestrator.AgentResult;
import com.example.Ece.agent.orchestrator.AgentStepEvent;
import com.example.Ece.agent.rag.KnowledgeChunker;
import com.example.Ece.agent.repository.AgentChatHistoryRepository;
import com.example.Ece.agent.repository.AgentChatHistoryRepository.ChatHistoryRow;
import com.example.Ece.agent.repository.AgentStepTraceRepository;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话历史的半永久化：把每次编排的终态写入 {@code agent_chat_history}，
 * 数据库不可用时退回本地 JSONL 文件，**任何情况下都不向调用方抛异常**。
 *
 * <p><b>为什么不抛。</b>历史落库是审计与评测所需的旁路，不是对话本身。
 * 让 MySQL 抖动导致用户拿不到回答，是把旁路的失败升级成主流程的失败。
 * 因此这里捕获全部异常、记日志、写兜底文件，然后正常返回。</p>
 *
 * <p><b>为什么还留一个文件兜底。</b>迁移是手工执行的（见 {@code docs/tomato-greenhouse-agent.md}），
 * 没跑迁移或数据库临时不可用时，历史会静默丢掉。文件兜底保证"至少不丢"，
 * 且它同时是回答轴评测（{@code tools/answer-eval-kit}）可以直接消费的格式。</p>
 *
 * <p><b>与进程内会话记忆的关系。</b>{@code SessionHistoryStore} 仍然照旧工作，两者互不影响：
 * 内存那份是喂给模型的上下文，只保留最近若干轮且**不含拒答**（拒答不进上下文，
 * 否则会污染后续轮次的判断）；本服务保存的是**全部终态**（含拒答），仅用于审计与评测。</p>
 */
@Service
public class AgentChatHistoryService {

    private static final Logger log = LoggerFactory.getLogger(AgentChatHistoryService.class);

    public static final String DEFAULT_FALLBACK_PATH = "logs/agent-chat-history-fallback.jsonl";
    private static final int MAX_FIELD_CHARS = 20000;

    private final AgentChatHistoryRepository repository;
    /** 工具调用审计轨迹。为 null 表示不写轨迹（单元测试默认如此）。 */
    private final AgentStepTraceRepository stepTraceRepository;
    private final Path fallbackPath;
    private final Object fallbackLock = new Object();

    @Autowired
    public AgentChatHistoryService(AgentChatHistoryRepository repository,
                                  AgentStepTraceRepository stepTraceRepository,
                                  @Value("${agent.chat.history.fallback-path:" + DEFAULT_FALLBACK_PATH + "}")
                                  String fallbackPath) {
        this(repository, stepTraceRepository, Paths.get(fallbackPath));
    }

    /** 不写工具调用轨迹的便捷构造（单元测试与纯历史场景用）。 */
    public AgentChatHistoryService(AgentChatHistoryRepository repository, Path fallbackPath) {
        this(repository, null, fallbackPath);
    }

    public AgentChatHistoryService(AgentChatHistoryRepository repository,
                                   AgentStepTraceRepository stepTraceRepository, Path fallbackPath) {
        this.repository = repository;
        this.stepTraceRepository = stepTraceRepository;
        this.fallbackPath = fallbackPath;
    }

    /**
     * 记录一次工具调用的审计轨迹（{@code agent_step_trace}）。
     *
     * <p><b>这里曾经是死代码</b>：表建了、{@code AgentStepTraceRepository} 写了，
     * 但零调用方——工具调用没有任何审计轨迹，而文档却宣称有。2026-09-26 端到端验证时发现。</p>
     *
     * <p>与 {@link #record} 同样**永不抛异常**：审计是旁路，写不进去不该影响对话。
     * 也**不做本地兜底**——轨迹是逐工具的细粒度数据，量比会话大得多，
     * 落文件会迅速膨胀且难以回查；API 与工具调用本身就带有 step 事件，
     * 丢了轨迹仍可从 SSE 事件流复原。</p>
     *
     * @param runId 该工具触及的模拟运行编号，可为 null（智能体会话本身不绑定运行）
     * @return true 表示已写入
     */
    public boolean recordStep(String sessionId, Long runId, int stepNo, String toolName, String inputDigest,
                              Map<String, Object> output, long durationMs, boolean degraded, String status) {
        if (stepTraceRepository == null) {
            return false;
        }
        String outputDigest = null;
        try {
            outputDigest = KnowledgeChunker.sha256(JSON.toJSONString(output));
        } catch (RuntimeException ignored) {
            // 输出里可能含不可序列化对象；轨迹的价值在"调过什么"，摘要算不出来就不记摘要。
            outputDigest = null;
        }
        try {
            stepTraceRepository.record(sessionId, runId, stepNo, toolName, inputDigest, outputDigest,
                    durationMs, degraded, status);
            return true;
        } catch (RuntimeException error) {
            log.warn("工具调用轨迹写入失败（不影响对话）：{}", error.toString());
            return false;
        }
    }

    /**
     * 记录一次编排的终态。**永不抛异常**——失败只记日志并落兜底文件。
     *
     * @return true 表示已持久化（数据库成功，或数据库失败但兜底文件写入成功）；
     *         false 表示数据库与兜底文件都失败，本次历史丢失。调用方无需据此改变行为。
     */
    public boolean record(String sessionId, String crop, String question, AgentResult result) {
        if (result == null) {
            return false;
        }
        ChatHistoryRow row = toRow(sessionId, crop, question, result);
        try {
            repository.insert(row);
            return true;
        } catch (RuntimeException error) {
            log.warn("会话历史写入数据库失败，改用本地兜底文件 {}：{}", fallbackPath, error.toString());
            return appendFallback(row);
        }
    }

    public List<ChatHistoryRow> listBySession(String sessionId, int limit) {
        return repository.listBySession(sessionId, limit);
    }

    public List<ChatHistoryRow> listRecent(int limit) {
        return repository.listRecent(limit);
    }

    /**
     * 导出为 JSONL，字段与 {@code tools/answer-eval-kit} 的抓取格式对齐，
     * 使回答轴评测可以直接消费历史而无需重跑平台。
     *
     * <p>注意这是**投影而非逐字节重放**：本地未存事件流，因此 {@code eventTypes} 固定为 {@code ["final"]}，
     * {@code toolTrace} 由落库的工具名列表还原。评测报告引用时应说明这一点。</p>
     */
    public String exportJsonl(int limit) {
        List<ChatHistoryRow> rows = repository.listRecent(limit);
        List<ChatHistoryRow> ordered = new ArrayList<ChatHistoryRow>(rows.size());
        for (int index = rows.size() - 1; index >= 0; index--) {
            ordered.add(rows.get(index));
        }
        StringBuilder builder = new StringBuilder();
        for (ChatHistoryRow row : ordered) {
            // 必须显式写 null：fastjson 默认丢弃 null 字段，会让 refusalReason/expect 这类键
            // 在 DONE 记录里直接消失，消费方就无法依赖固定 schema。Jackson 默认也是写 null 的。
            builder.append(JSON.toJSONString(toExportRecord(row),
                    SerializerFeature.WriteMapNullValue)).append('\n');
        }
        return builder.toString();
    }

    private ChatHistoryRow toRow(String sessionId, String crop, String question, AgentResult result) {
        ChatHistoryRow row = new ChatHistoryRow();
        row.sessionId = sessionId == null || sessionId.trim().isEmpty() ? "anonymous" : sessionId.trim();
        row.crop = crop;
        row.question = truncate(question);
        row.answer = truncate(result.getAnswer());
        row.status = result.getStatus() == null ? "ERROR" : result.getStatus().name();
        row.refusalReason = refusalReasonOf(result);
        row.citationCount = result.getCitations().size();
        row.citationsJson = result.getCitations().isEmpty()
                ? null : JSON.toJSONString(result.getCitations());
        row.steps = result.getSteps();
        row.tools = toolsOf(result);
        row.createdAt = null; // 交给数据库/仓库填当前时间
        return row;
    }

    /** 从 final 事件的 payload 里取拒答/拦截原因；DONE 分支该字段为 null。 */
    private String refusalReasonOf(AgentResult result) {
        List<AgentStepEvent> events = result.getEvents();
        if (events == null) {
            return null;
        }
        for (int index = events.size() - 1; index >= 0; index--) {
            AgentStepEvent event = events.get(index);
            if (!"final".equals(event.getType())) {
                continue;
            }
            Object reason = event.getPayload().get("reason");
            return reason == null ? null : String.valueOf(reason);
        }
        return null;
    }

    private String toolsOf(AgentResult result) {
        List<AgentStepEvent> events = result.getEvents();
        if (events == null) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        for (AgentStepEvent event : events) {
            if (!"step".equals(event.getType()) || event.getToolName() == null) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(event.getToolName());
        }
        return builder.length() == 0 ? null : truncate(builder.toString(), 512);
    }

    private Map<String, Object> toExportRecord(ChatHistoryRow row) {
        Map<String, Object> record = new LinkedHashMap<String, Object>();
        record.put("id", row.id == null ? null : String.valueOf(row.id));
        record.put("partition", "history");
        record.put("crop", row.crop);
        record.put("question", row.question);
        record.put("expect", null);
        record.put("sessionId", row.sessionId);
        record.put("status", row.status);
        record.put("answer", row.answer);
        record.put("citations", row.citationsJson == null
                ? new ArrayList<Object>() : JSON.parseArray(row.citationsJson));
        record.put("refusalReason", row.refusalReason);
        record.put("steps", Integer.valueOf(row.steps));
        record.put("toolTrace", row.tools == null ? new ArrayList<String>() : splitTools(row.tools));
        record.put("eventTypes", new ArrayList<String>(java.util.Collections.singletonList("final")));
        record.put("transportError", null);
        record.put("capturedAt", row.createdAt);
        return record;
    }

    private List<String> splitTools(String tools) {
        List<String> result = new ArrayList<String>();
        for (String part : tools.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private boolean appendFallback(ChatHistoryRow row) {
        Map<String, Object> record = toExportRecord(row);
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
                log.error("会话历史兜底写入同样失败，本次历史丢失：{}", error.toString());
                return false;
            }
        }
    }

    private String truncate(String value) {
        return truncate(value, MAX_FIELD_CHARS);
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "…(截断)";
    }
}
