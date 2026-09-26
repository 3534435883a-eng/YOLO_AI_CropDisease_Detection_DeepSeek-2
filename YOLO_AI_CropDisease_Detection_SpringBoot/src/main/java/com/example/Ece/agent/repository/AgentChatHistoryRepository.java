package com.example.Ece.agent.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * 智能体会话历史的持久化读写。
 *
 * <p>本类只负责 SQL，不吞异常：写失败要能被上层看见（{@code AgentChatHistoryService} 据此落本地兜底），
 * 所以这里让 {@code DataAccessException} 照常抛出，不做兜底。</p>
 */
@Repository
public class AgentChatHistoryRepository {

    private static final String INSERT_SQL = "INSERT INTO agent_chat_history "
            + "(session_id, crop, question, answer, status, refusal_reason, citation_count, "
            + "citations_json, steps, tools, created_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String SELECT_COLUMNS = "SELECT id, session_id, crop, question, answer, status, "
            + "refusal_reason, citation_count, citations_json, steps, tools, created_at "
            + "FROM agent_chat_history ";

    private static final RowMapper<ChatHistoryRow> ROW_MAPPER = new RowMapper<ChatHistoryRow>() {
        public ChatHistoryRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            ChatHistoryRow row = new ChatHistoryRow();
            row.id = rs.getLong("id");
            row.sessionId = rs.getString("session_id");
            row.crop = rs.getString("crop");
            row.question = rs.getString("question");
            row.answer = rs.getString("answer");
            row.status = rs.getString("status");
            row.refusalReason = rs.getString("refusal_reason");
            row.citationCount = rs.getInt("citation_count");
            row.citationsJson = rs.getString("citations_json");
            row.steps = rs.getInt("steps");
            row.tools = rs.getString("tools");
            Timestamp created = rs.getTimestamp("created_at");
            row.createdAt = created == null ? null : created.toString();
            return row;
        }
    };

    private final JdbcTemplate jdbcTemplate;

    public AgentChatHistoryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(ChatHistoryRow row) {
        jdbcTemplate.update(INSERT_SQL, row.sessionId, row.crop, row.question, row.answer, row.status,
                row.refusalReason, Integer.valueOf(row.citationCount), row.citationsJson,
                Integer.valueOf(row.steps), row.tools,
                row.createdAt == null ? new Timestamp(System.currentTimeMillis()) : Timestamp.valueOf(row.createdAt));
    }

    /** 某个会话的最近若干轮，按时间正序返回（便于直接当时间线读）。 */
    public List<ChatHistoryRow> listBySession(String sessionId, int limit) {
        int effectiveLimit = limit <= 0 ? 50 : limit;
        List<ChatHistoryRow> rows = jdbcTemplate.query(
                SELECT_COLUMNS + "WHERE session_id = ? ORDER BY created_at DESC, id DESC LIMIT ?",
                ROW_MAPPER, sessionId, Integer.valueOf(effectiveLimit));
        List<ChatHistoryRow> reversed = new ArrayList<ChatHistoryRow>(rows.size());
        for (int index = rows.size() - 1; index >= 0; index--) {
            reversed.add(rows.get(index));
        }
        return reversed;
    }

    /** 全局最近若干条，按时间倒序返回（最近的在最前）。 */
    public List<ChatHistoryRow> listRecent(int limit) {
        int effectiveLimit = limit <= 0 ? 50 : limit;
        return jdbcTemplate.query(SELECT_COLUMNS + "ORDER BY created_at DESC, id DESC LIMIT ?",
                ROW_MAPPER, Integer.valueOf(effectiveLimit));
    }

    public int count() {
        Integer total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM agent_chat_history", Integer.class);
        return total == null ? 0 : total.intValue();
    }

    /** 一行历史记录。字段与 `agent_chat_history` 表一一对应。 */
    public static class ChatHistoryRow {
        public Long id;
        public String sessionId;
        public String crop;
        public String question;
        public String answer;
        public String status;
        public String refusalReason;
        public int citationCount;
        public String citationsJson;
        public int steps;
        public String tools;
        public String createdAt;
    }
}
