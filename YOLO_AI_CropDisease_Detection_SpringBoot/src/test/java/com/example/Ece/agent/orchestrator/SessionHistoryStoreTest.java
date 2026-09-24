package com.example.Ece.agent.orchestrator;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionHistoryStoreTest {
    @Test
    void keepsRecentTurnsAndSeparatesSessions() {
        SessionHistoryStore store = new SessionHistoryStore(2);
        store.append("a", "问题1", "回答1");
        store.append("a", "问题2", "回答2");
        store.append("a", "问题3", "回答3");
        store.append("b", "另一个问题", "另一个回答");

        List<Map<String, Object>> a = store.snapshot("a");
        assertEquals(4, a.size(), "每轮包含 user 与 assistant 两条消息，且只保留最近两轮");
        assertEquals("问题2", a.get(0).get("content"));
        assertEquals("回答3", a.get(3).get("content"));
        assertTrue(store.snapshot("missing").isEmpty());
        assertEquals("另一个问题", store.snapshot("b").get(0).get("content"));
    }

    @Test
    void evictsLeastRecentlyUsedSessionWhenSessionLimitIsReached() {
        SessionHistoryStore store = new SessionHistoryStore(2, 2);
        store.append("a", "问题A", "回答A");
        store.append("b", "问题B", "回答B");
        store.snapshot("a");
        store.append("c", "问题C", "回答C");

        assertTrue(store.snapshot("b").isEmpty());
        assertEquals("问题A", store.snapshot("a").get(0).get("content"));
        assertEquals("问题C", store.snapshot("c").get(0).get("content"));
    }
}
