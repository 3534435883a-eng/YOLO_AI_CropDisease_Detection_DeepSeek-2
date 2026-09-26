package com.example.Ece.agent.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.example.Ece.agent.orchestrator.AgentResult;
import com.example.Ece.agent.orchestrator.AgentStepEvent;
import com.example.Ece.agent.repository.AgentChatHistoryRepository;
import com.example.Ece.agent.repository.AgentChatHistoryRepository.ChatHistoryRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话历史半永久化的测试。重点是两条不变量：
 * **数据库可用时不写兜底文件**、**任何失败都不向上抛异常**。
 */
class AgentChatHistoryServiceTest {

    private static final String SESSION = "session-1";
    private static final String QUESTION = "番茄叶片出现褐色轮纹斑，先核对什么？";

    private Map<String, Object> citation() {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("index", Integer.valueOf(1));
        item.put("name", "番茄早疫病");
        return item;
    }

    private AgentResult doneResult() {
        List<AgentStepEvent> events = new ArrayList<AgentStepEvent>();
        events.add(new AgentStepEvent("step", 1, "knowledge.search", "检索", new LinkedHashMap<String, Object>()));
        events.add(new AgentStepEvent("step", 2, "FINALIZE", "整理", new LinkedHashMap<String, Object>()));
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("status", "DONE");
        payload.put("reason", null);
        payload.put("citationCount", Integer.valueOf(1));
        payload.put("steps", Integer.valueOf(2));
        events.add(new AgentStepEvent("final", 2, null, "疑似早疫病，请复核。[1]", payload));
        return new AgentResult("疑似早疫病，请复核。[1]",
                new ArrayList<Map<String, Object>>(Collections.singletonList(citation())),
                events, 2, AgentResult.Status.DONE);
    }

    private AgentResult refusedResult() {
        List<AgentStepEvent> events = new ArrayList<AgentStepEvent>();
        events.add(new AgentStepEvent("step", 1, "knowledge.search", "检索", new LinkedHashMap<String, Object>()));
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("status", "REFUSED");
        payload.put("reason", "NO_RELIABLE_EVIDENCE");
        payload.put("citationCount", Integer.valueOf(0));
        payload.put("steps", Integer.valueOf(1));
        events.add(new AgentStepEvent("final", 1, null, "资料库中未找到足够依据……", payload));
        return new AgentResult("资料库中未找到足够依据……", new ArrayList<Map<String, Object>>(),
                events, 1, AgentResult.Status.REFUSED);
    }

    @Test
    void writesToDatabaseAndLeavesNoFallbackFile(@TempDir Path dir) {
        AgentChatHistoryRepository repository = mock(AgentChatHistoryRepository.class);
        Path fallback = dir.resolve("fallback.jsonl");
        AgentChatHistoryService service = new AgentChatHistoryService(repository, fallback);

        assertTrue(service.record(SESSION, "番茄", QUESTION, doneResult()));

        ArgumentCaptor<ChatHistoryRow> captor = ArgumentCaptor.forClass(ChatHistoryRow.class);
        verify(repository).insert(captor.capture());
        ChatHistoryRow row = captor.getValue();
        assertEquals(SESSION, row.sessionId);
        assertEquals("番茄", row.crop);
        assertEquals(QUESTION, row.question);
        assertEquals("疑似早疫病，请复核。[1]", row.answer);
        assertEquals("DONE", row.status);
        assertEquals(1, row.citationCount);
        assertEquals(2, row.steps);
        assertNotNull(row.citationsJson);
        assertFalse(Files.exists(fallback), "数据库写入成功时不应产生兜底文件");
    }

    @Test
    void fallsBackToLocalFileWhenDatabaseFails(@TempDir Path dir) throws Exception {
        AgentChatHistoryRepository repository = mock(AgentChatHistoryRepository.class);
        doThrow(new IllegalStateException("db down")).when(repository).insert(any(ChatHistoryRow.class));
        Path fallback = dir.resolve("nested").resolve("fallback.jsonl");
        AgentChatHistoryService service = new AgentChatHistoryService(repository, fallback);

        assertTrue(service.record(SESSION, "番茄", QUESTION, refusedResult()), "兜底成功应返回 true");

        assertTrue(Files.isRegularFile(fallback), "数据库失败时必须落兜底文件");
        List<String> lines = Files.readAllLines(fallback, StandardCharsets.UTF_8);
        assertEquals(1, lines.size());
        JSONObject record = JSON.parseObject(lines.get(0));
        assertEquals(SESSION, record.getString("sessionId"));
        assertEquals("REFUSED", record.getString("status"));
        assertEquals("NO_RELIABLE_EVIDENCE", record.getString("refusalReason"));
        assertEquals(Boolean.TRUE, record.getBoolean("fallback"));
        assertTrue(record.getString("answer").contains("未找到足够依据"));
    }

    @Test
    void returnsFalseAndStillDoesNotThrowWhenBothStoresFail(@TempDir Path dir) throws Exception {
        AgentChatHistoryRepository repository = mock(AgentChatHistoryRepository.class);
        doThrow(new IllegalStateException("db down")).when(repository).insert(any(ChatHistoryRow.class));
        // 兜底路径的父目录是一个普通文件 → 建目录必然失败
        Path blocker = dir.resolve("blocker");
        Files.write(blocker, "x".getBytes(StandardCharsets.UTF_8));
        AgentChatHistoryService service = new AgentChatHistoryService(repository, blocker.resolve("fallback.jsonl"));

        boolean recorded = service.record(SESSION, "番茄", QUESTION, doneResult());

        assertFalse(recorded, "两处都失败时应返回 false");
    }

    @Test
    void extractsRefusalReasonToolsAndOrderFromTheFinalEvent(@TempDir Path dir) {
        AgentChatHistoryRepository repository = mock(AgentChatHistoryRepository.class);
        AgentChatHistoryService service = new AgentChatHistoryService(repository, dir.resolve("f.jsonl"));

        service.record(SESSION, "番茄", QUESTION, refusedResult());

        ArgumentCaptor<ChatHistoryRow> captor = ArgumentCaptor.forClass(ChatHistoryRow.class);
        verify(repository).insert(captor.capture());
        ChatHistoryRow row = captor.getValue();
        assertEquals("NO_RELIABLE_EVIDENCE", row.refusalReason);
        assertEquals("knowledge.search", row.tools);
        assertEquals(0, row.citationCount);
    }

    @Test
    void ignoresNullResultWithoutTouchingTheDatabase(@TempDir Path dir) {
        AgentChatHistoryRepository repository = mock(AgentChatHistoryRepository.class);
        AgentChatHistoryService service = new AgentChatHistoryService(repository, dir.resolve("f.jsonl"));

        assertFalse(service.record(SESSION, "番茄", QUESTION, null));
        verify(repository, never()).insert(any(ChatHistoryRow.class));
    }

    @Test
    void exportJsonlMatchesTheAnswerEvalKitSchema(@TempDir Path dir) {
        AgentChatHistoryRepository repository = mock(AgentChatHistoryRepository.class);
        ChatHistoryRow row = new ChatHistoryRow();
        row.id = Long.valueOf(7L);
        row.sessionId = SESSION;
        row.crop = "番茄";
        row.question = QUESTION;
        row.answer = "疑似早疫病，请复核。[1]";
        row.status = "DONE";
        row.refusalReason = null;
        row.citationCount = 1;
        row.citationsJson = JSON.toJSONString(Arrays.asList(citation()));
        row.steps = 2;
        row.tools = "knowledge.search,FINALIZE";
        row.createdAt = "2026-09-26 15:00:00.0";
        when(repository.listRecent(10)).thenReturn(new ArrayList<ChatHistoryRow>(Arrays.asList(row)));
        AgentChatHistoryService service = new AgentChatHistoryService(repository, dir.resolve("f.jsonl"));

        String jsonl = service.exportJsonl(10);

        List<String> lines = Arrays.asList(jsonl.split("\n"));
        assertEquals(1, lines.size());
        JSONObject record = JSON.parseObject(lines.get(0));
        // 评测工具链（tools/answer-eval-kit）要求的字段
        for (String key : Arrays.asList("id", "partition", "crop", "question", "status", "answer",
                "citations", "refusalReason", "steps", "toolTrace", "capturedAt")) {
            assertTrue(record.containsKey(key), "导出缺少字段 " + key);
        }
        assertEquals("7", record.getString("id"));
        assertEquals("history", record.getString("partition"));
        assertEquals(Arrays.asList("knowledge.search", "FINALIZE"),
                JSON.parseArray(record.getJSONArray("toolTrace").toJSONString(), String.class));
        assertEquals(1, record.getJSONArray("citations").size());
    }
}
