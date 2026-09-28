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
 * 推演运行记录的持久化读写。
 *
 * <p>本类只负责 SQL，**不吞异常**：写失败要能被上层看见（{@code AgriPlanHistoryService}
 * 据此落本地兜底），所以让 {@code DataAccessException} 照常抛出。这与
 * {@code AgentChatHistoryRepository} 是同一条约定。</p>
 */
@Repository
public class AgriPlanRunRepository {

    private static final String INSERT_SQL = "INSERT INTO agent_plan_run "
            + "(seed, days, question, situation_json, answer_markdown, structured_json, "
            + "banner_injected, stream_mode, fallback_reason, baseline_batch_id, baseline_json, "
            + "sections, missing_fields, elapsed_ms, created_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String SELECT_COLUMNS = "SELECT id, seed, days, question, situation_json, "
            + "answer_markdown, structured_json, banner_injected, stream_mode, fallback_reason, "
            + "baseline_batch_id, baseline_json, sections, missing_fields, elapsed_ms, created_at "
            + "FROM agent_plan_run ";

    private static final RowMapper<PlanRunRow> ROW_MAPPER = new RowMapper<PlanRunRow>() {
        public PlanRunRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            PlanRunRow row = new PlanRunRow();
            row.id = Long.valueOf(rs.getLong("id"));
            row.seed = rs.getLong("seed");
            row.days = rs.getInt("days");
            row.question = rs.getString("question");
            row.situationJson = rs.getString("situation_json");
            row.answerMarkdown = rs.getString("answer_markdown");
            row.structuredJson = rs.getString("structured_json");
            row.bannerInjected = rs.getBoolean("banner_injected");
            row.streamMode = rs.getString("stream_mode");
            row.fallbackReason = rs.getString("fallback_reason");
            row.baselineBatchId = rs.getString("baseline_batch_id");
            row.baselineJson = rs.getString("baseline_json");
            row.sections = rs.getString("sections");
            row.missingFields = rs.getString("missing_fields");
            row.elapsedMs = rs.getLong("elapsed_ms");
            Timestamp created = rs.getTimestamp("created_at");
            row.createdAt = created == null ? null : created.toString();
            return row;
        }
    };

    private final JdbcTemplate jdbcTemplate;

    public AgriPlanRunRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 写入一条并返回自增主键；失败时把 {@code DataAccessException} 抛给上层。 */
    public long insert(PlanRunRow row) {
        jdbcTemplate.update(INSERT_SQL, Long.valueOf(row.seed), Integer.valueOf(row.days), row.question,
                row.situationJson, row.answerMarkdown, row.structuredJson,
                Boolean.valueOf(row.bannerInjected), row.streamMode, row.fallbackReason,
                row.baselineBatchId, row.baselineJson, row.sections, row.missingFields,
                Long.valueOf(row.elapsedMs),
                row.createdAt == null
                        ? new Timestamp(System.currentTimeMillis()) : Timestamp.valueOf(row.createdAt));
        Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return id == null ? 0L : id.longValue();
    }

    /** 最近的若干条，**按时间倒序**（最近的在前）。 */
    public List<PlanRunRow> listRecent(int limit) {
        int effectiveLimit = limit <= 0 ? 20 : limit;
        return jdbcTemplate.query(SELECT_COLUMNS + "ORDER BY created_at DESC, id DESC LIMIT ?",
                ROW_MAPPER, Integer.valueOf(effectiveLimit));
    }

    public PlanRunRow findById(long id) {
        List<PlanRunRow> rows = jdbcTemplate.query(SELECT_COLUMNS + "WHERE id = ?",
                ROW_MAPPER, Long.valueOf(id));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int count() {
        Integer total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM agent_plan_run", Integer.class);
        return total == null ? 0 : total.intValue();
    }

    /** 一行推演记录，字段与 {@code agent_plan_run} 表一一对应。 */
    public static class PlanRunRow {
        public Long id;
        public long seed;
        public int days;
        public String question;
        public String situationJson;
        public String answerMarkdown;
        public String structuredJson;
        public boolean bannerInjected;
        public String streamMode;
        public String fallbackReason;
        public String baselineBatchId;
        public String baselineJson;
        public String sections;
        public String missingFields;
        public long elapsedMs;
        public String createdAt;

        /** 逗号分隔字段转列表，供下发给前端。 */
        public List<String> split(String value) {
            List<String> parts = new ArrayList<String>();
            if (value == null) {
                return parts;
            }
            for (String part : value.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    parts.add(trimmed);
                }
            }
            return parts;
        }
    }
}
