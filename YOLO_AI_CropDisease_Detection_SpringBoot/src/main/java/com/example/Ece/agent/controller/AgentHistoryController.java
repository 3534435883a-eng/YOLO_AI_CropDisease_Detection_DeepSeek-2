package com.example.Ece.agent.controller;

import com.example.Ece.agent.repository.AgentChatHistoryRepository.ChatHistoryRow;
import com.example.Ece.agent.service.AgentChatHistoryService;
import com.example.Ece.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话历史查询接口。数据来自 {@code agent_chat_history} 表（由 {@code AgentChatHistoryService} 写入）。
 *
 * <p>三个接口的分工：按会话查（前端历史会话用）、查最近（总览用）、导出 JSONL（离线评测用）。</p>
 */
@RestController
@RequestMapping("/ai/agent/history")
public class AgentHistoryController {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 500;

    private final AgentChatHistoryService historyService;

    public AgentHistoryController(AgentChatHistoryService historyService) {
        this.historyService = historyService;
    }

    /** 某个会话的历史，按时间正序（可直接当时间线渲染）。 */
    @GetMapping
    public Result<?> bySession(@RequestParam("sessionId") String sessionId,
                               @RequestParam(value = "limit", required = false) Integer limit) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return Result.error("SESSION_ID_REQUIRED", "缺少 sessionId");
        }
        return Result.success(rows(historyService.listBySession(sessionId.trim(), bound(limit))));
    }

    /** 全局最近若干条，按时间倒序（最近的在最前）。 */
    @GetMapping("/recent")
    public Result<?> recent(@RequestParam(value = "limit", required = false) Integer limit) {
        return Result.success(rows(historyService.listRecent(bound(limit))));
    }

    /**
     * 导出 JSONL，字段与 {@code tools/answer-eval-kit} 的抓取格式对齐，
     * 供回答轴评测直接消费而无需重跑平台。
     *
     * <p><b>这里刻意不套 {@code Result} 信封</b>：导出的是文件内容，包一层 JSON 会让调用方
     * 无法直接落盘。这是本项目唯一一处偏离统一信封的接口，已在此说明。</p>
     */
    @GetMapping(value = "/export", produces = "application/x-ndjson;charset=UTF-8")
    public String export(@RequestParam(value = "limit", required = false) Integer limit) {
        return historyService.exportJsonl(bound(limit));
    }

    private int bound(Integer limit) {
        if (limit == null || limit.intValue() <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit.intValue(), MAX_LIMIT);
    }

    private List<Map<String, Object>> rows(List<ChatHistoryRow> rows) {
        List<Map<String, Object>> payload = new ArrayList<Map<String, Object>>();
        for (ChatHistoryRow row : rows) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("id", row.id);
            item.put("sessionId", row.sessionId);
            item.put("crop", row.crop);
            item.put("question", row.question);
            item.put("answer", row.answer);
            item.put("status", row.status);
            item.put("refusalReason", row.refusalReason);
            item.put("citationCount", Integer.valueOf(row.citationCount));
            item.put("steps", Integer.valueOf(row.steps));
            item.put("tools", row.tools);
            item.put("createdAt", row.createdAt);
            payload.add(item);
        }
        return payload;
    }
}
