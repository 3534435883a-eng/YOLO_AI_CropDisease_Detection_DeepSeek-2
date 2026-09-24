package com.example.Ece.agent.orchestrator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 有界的进程内会话记忆；只保存用户问题和最终答复，不保存检索证据或内部提示。 */
public class SessionHistoryStore {
    public static final int DEFAULT_MAX_TURNS = 8;
    public static final int DEFAULT_MAX_SESSIONS = 256;
    private final int maxTurns;
    private final int maxSessions;
    private final Map<String, List<Map<String, Object>>> sessions =
            new LinkedHashMap<String, List<Map<String, Object>>>(16, 0.75f, true);

    public SessionHistoryStore() {
        this(DEFAULT_MAX_TURNS, DEFAULT_MAX_SESSIONS);
    }

    public SessionHistoryStore(int maxTurns) {
        this(maxTurns, DEFAULT_MAX_SESSIONS);
    }

    public SessionHistoryStore(int maxTurns, int maxSessions) {
        if (maxTurns < 1 || maxSessions < 1) {
            throw new IllegalArgumentException("history limits must be positive");
        }
        this.maxTurns = maxTurns;
        this.maxSessions = maxSessions;
    }

    public synchronized List<Map<String, Object>> snapshot(String sessionId) {
        List<Map<String, Object>> source = sessions.get(normalize(sessionId));
        return source == null ? new ArrayList<Map<String, Object>>() : copy(source);
    }

    public synchronized void append(String sessionId, String question, String answer) {
        String key = normalize(sessionId);
        List<Map<String, Object>> history = sessions.get(key);
        if (history == null) {
            if (sessions.size() >= maxSessions) {
                sessions.remove(sessions.keySet().iterator().next());
            }
            history = new ArrayList<Map<String, Object>>();
            sessions.put(key, history);
        }
        history.add(message("user", question));
        history.add(message("assistant", answer));
        while (history.size() > maxTurns * 2) {
            history.remove(0);
            history.remove(0);
        }
    }

    private List<Map<String, Object>> copy(List<Map<String, Object>> source) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> message : source) {
            result.add(new LinkedHashMap<String, Object>(message));
        }
        return result;
    }

    private Map<String, Object> message(String role, String content) {
        Map<String, Object> message = new LinkedHashMap<String, Object>();
        message.put("role", role);
        message.put("content", content == null ? "" : content);
        return message;
    }

    private String normalize(String sessionId) {
        return sessionId == null || sessionId.trim().isEmpty() ? "anonymous" : sessionId.trim();
    }
}
