package com.example.Ece.agent.parameter;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;

/**
 * 参数出处登记的读写。
 *
 * <p>表上有 {@code UNIQUE KEY (parameter_code, version)}，因此用 upsert：反复刷新只更新不重复。
 * 与 {@code AgentChatHistoryRepository} 一样，本类只负责 SQL、不吞异常。</p>
 */
@Repository
public class ParameterSourceRepository {

    private static final String UPSERT_SQL = "INSERT INTO agent_parameter_source "
            + "(parameter_code, parameter_name, value_text, unit, source_name, source_url, version, created_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
            + "ON DUPLICATE KEY UPDATE parameter_name = VALUES(parameter_name), value_text = VALUES(value_text), "
            + "unit = VALUES(unit), source_name = VALUES(source_name), source_url = VALUES(source_url), "
            + "created_at = VALUES(created_at)";

    private static final String SELECT_COLUMNS = "SELECT parameter_code, parameter_name, value_text, unit, "
            + "id, source_name, source_url, version FROM agent_parameter_source ";

    private static final RowMapper<ParameterSource> ROW_MAPPER = new RowMapper<ParameterSource>() {
        public ParameterSource mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ParameterSource(rs.getLong("id"), rs.getString("parameter_code"), rs.getString("parameter_name"),
                    rs.getString("value_text"), rs.getString("unit"), rs.getString("source_name"),
                    rs.getString("source_url"), rs.getString("version"));
        }
    };

    private final JdbcTemplate jdbcTemplate;

    public ParameterSourceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void upsert(ParameterSource source) {
        jdbcTemplate.update(UPSERT_SQL, source.getCode(), source.getName(), source.getValueText(),
                source.getUnit(), source.getSourceName(), source.getSourceUrl(), source.getVersion(),
                new Timestamp(System.currentTimeMillis()));
    }

    public List<ParameterSource> listByVersion(String version) {
        return jdbcTemplate.query(SELECT_COLUMNS + "WHERE version = ? ORDER BY parameter_code", ROW_MAPPER, version);
    }

    public List<ParameterSource> listAll() {
        return jdbcTemplate.query(SELECT_COLUMNS + "ORDER BY parameter_code", ROW_MAPPER);
    }

    public int count() {
        Integer total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM agent_parameter_source", Integer.class);
        return total == null ? 0 : total.intValue();
    }

    /** 清掉非当前版本的旧登记，避免历史版本混在列表里。返回删除行数。 */
    public int deleteOtherVersions(String keepVersion) {
        return jdbcTemplate.update("DELETE FROM agent_parameter_source WHERE version <> ?", keepVersion);
    }

    /**
     * 清掉本版本的全部登记，供刷新时重建。
     *
     * <p>为什么必须清：只靠 upsert 保证不了正确性。2026-09-26 实测踩过——
     * 一次失败刷新在撞上超长编码前已插入部分行，随后编码格式变更，
     * 旧格式的行因**版本号未变**而既不被覆盖也不被 {@code deleteOtherVersions} 清掉，
     * 于是总数（158）比本次登记数（108）还多。登记表是**快照**不是事件日志，重建才是对的。</p>
     */
    public int deleteByVersion(String version) {
        return jdbcTemplate.update("DELETE FROM agent_parameter_source WHERE version = ?", version);
    }
}
