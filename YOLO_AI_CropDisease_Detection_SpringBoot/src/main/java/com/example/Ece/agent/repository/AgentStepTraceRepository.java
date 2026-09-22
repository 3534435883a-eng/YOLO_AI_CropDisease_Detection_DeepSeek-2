package com.example.Ece.agent.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;

/** 智能体每一步的审计与可观测落库。 */
@Repository
public class AgentStepTraceRepository {

    private static final String INSERT_SQL = "INSERT INTO agent_step_trace "
            + "(session_id, run_id, step_no, tool_name, input_digest, output_digest, duration_ms, degraded, status, created_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private final JdbcTemplate jdbcTemplate;

    public AgentStepTraceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void record(String sessionId, Long runId, int stepNo, String toolName, String inputDigest,
                       String outputDigest, long durationMs, boolean degraded, String status) {
        jdbcTemplate.update(INSERT_SQL, sessionId, runId, Integer.valueOf(stepNo), toolName, inputDigest,
                outputDigest, Long.valueOf(durationMs), Integer.valueOf(degraded ? 1 : 0), status,
                new Timestamp(System.currentTimeMillis()));
    }
}
