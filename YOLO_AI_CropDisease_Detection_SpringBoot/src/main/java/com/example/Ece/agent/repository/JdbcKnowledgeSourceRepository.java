package com.example.Ece.agent.repository;

import com.example.Ece.agent.rag.KnowledgeSource;
import com.example.Ece.agent.rag.KnowledgeSourceRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

/**
 * 知识来源登记的 JDBC 实现（表 agent_knowledge_source 为 create-only 迁移创建）。
 *
 * <p>{@link #save} 是**幂等 upsert**：表上 (source_code, version) 有唯一键，
 * 纯 INSERT 会让同一来源的第二次 ingest 直接报唯一键冲突——实测 {@code /ai/knowledge/reingest}
 * 就是这样返回 500 的。来源元数据（名称/层级/许可）以最后一次登记为准。</p>
 */
@Repository
public class JdbcKnowledgeSourceRepository implements KnowledgeSourceRepository {

    private static final String INSERT_SQL = "INSERT INTO agent_knowledge_source "
            + "(source_code, source_name, source_type, authority_level, url, license_note, version, created_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
            + "ON DUPLICATE KEY UPDATE source_name = VALUES(source_name), source_type = VALUES(source_type), "
            + "authority_level = VALUES(authority_level), url = VALUES(url), "
            + "license_note = VALUES(license_note)";

    private final JdbcTemplate jdbcTemplate;

    public JdbcKnowledgeSourceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(KnowledgeSource source) {
        jdbcTemplate.update(INSERT_SQL, source.getSourceCode(), source.getSourceName(), source.getSourceType(),
                Integer.valueOf(source.getAuthorityLevel()), source.getUrl(), source.getLicenseNote(),
                source.getVersion(), new Timestamp(System.currentTimeMillis()));
    }

    public List<KnowledgeSource> findAll() {
        return jdbcTemplate.query("SELECT * FROM agent_knowledge_source ORDER BY authority_level ASC, source_code ASC",
                new org.springframework.jdbc.core.RowMapper<KnowledgeSource>() {
                    public KnowledgeSource mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
                        return new KnowledgeSource(rs.getString("source_code"), rs.getString("source_name"),
                                rs.getString("source_type"), rs.getInt("authority_level"), rs.getString("url"),
                                rs.getString("license_note"), rs.getString("version"));
                    }
                });
    }

    public KnowledgeSource findByCode(final String sourceCode, final String version) {
        List<KnowledgeSource> found = jdbcTemplate.query(
                "SELECT * FROM agent_knowledge_source WHERE source_code = ? AND version = ?",
                new Object[]{sourceCode, version},
                new org.springframework.jdbc.core.RowMapper<KnowledgeSource>() {
                    public KnowledgeSource mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
                        return new KnowledgeSource(rs.getString("source_code"), rs.getString("source_name"),
                                rs.getString("source_type"), rs.getInt("authority_level"), rs.getString("url"),
                                rs.getString("license_note"), rs.getString("version"));
                    }
                });
        return found.isEmpty() ? null : found.get(0);
    }
}
