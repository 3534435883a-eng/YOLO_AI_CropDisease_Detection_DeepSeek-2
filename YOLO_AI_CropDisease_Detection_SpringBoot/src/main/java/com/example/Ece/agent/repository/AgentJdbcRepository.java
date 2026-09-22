package com.example.Ece.agent.repository;

import com.example.Ece.agent.model.SimulationState;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** JDBC-only persistence boundary for the agent tables. Locking methods are used only inside service transactions. */
@Repository
public class AgentJdbcRepository {
    private final JdbcTemplate jdbcTemplate;

    public AgentJdbcRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public RunRow findRun(Long runId) {
        return queryOne("SELECT * FROM agent_run WHERE id = ?", RUN_ROW_MAPPER, runId);
    }

    public RunRow findRunForUpdate(Long runId) {
        return queryOne("SELECT * FROM agent_run WHERE id = ? FOR UPDATE", RUN_ROW_MAPPER, runId);
    }

    public RunRow findActiveRun() {
        return queryOne("SELECT * FROM agent_run WHERE active_slot = 1 ORDER BY updated_at DESC LIMIT 1", RUN_ROW_MAPPER);
    }

    public RunRow findActiveRunForUpdate() {
        return queryOne("SELECT * FROM agent_run WHERE active_slot = 1 ORDER BY updated_at DESC LIMIT 1 FOR UPDATE", RUN_ROW_MAPPER);
    }

    public RunRow findActiveRunningRunForUpdate() {
        return queryOne("SELECT * FROM agent_run WHERE active_slot = 1 AND status = 'RUNNING' "
                + "ORDER BY updated_at DESC LIMIT 1 FOR UPDATE", RUN_ROW_MAPPER);
    }

    public void deactivateActiveRuns(LocalDateTime now) {
        jdbcTemplate.update("UPDATE agent_run SET active_slot = NULL, updated_at = ?, version = version + 1 "
                        + "WHERE active_slot = 1", timestamp(now));
    }

    public long insertRun(RunRow row) {
        String sql = "INSERT INTO agent_run (run_code, greenhouse_code, crop_type, growth_stage, status, active_slot, "
                + "step_no, simulated_at, tick_minutes, seed, model_version, baseline_json, source_greenhouse_record_id, "
                + "created_by, version, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        return insertForKey(sql, row.runCode, row.greenhouseCode, row.cropType, row.growthStage, row.status,
                row.activeSlot, row.stepNo, row.simulatedAt, row.tickMinutes, row.seed, row.modelVersion,
                row.baselineJson, row.sourceGreenhouseRecordId, row.createdBy, row.version, row.createdAt, row.updatedAt);
    }

    public void updateRun(RunRow row) {
        jdbcTemplate.update("UPDATE agent_run SET status = ?, active_slot = ?, step_no = ?, simulated_at = ?, "
                        + "updated_at = ?, version = version + 1 WHERE id = ?",
                row.status, row.activeSlot, row.stepNo, timestamp(row.simulatedAt), timestamp(row.updatedAt), row.id);
    }

    public GreenhouseSeed findGreenhouseSeed(Long greenhouseId) {
        if (greenhouseId == null) {
            return null;
        }
        return queryOne("SELECT id, greenhouse_name, crop_type, temperature, air_humidity, soil_humidity, "
                        + "co2_concentration, soil_ph, light_intensity FROM greenhouse WHERE id = ?",
                GREENHOUSE_SEED_MAPPER, greenhouseId);
    }

    public SnapshotRow findLatestSnapshot(Long runId) {
        return queryOne("SELECT * FROM agent_environment_snapshot WHERE run_id = ? ORDER BY step_no DESC LIMIT 1",
                SNAPSHOT_ROW_MAPPER, runId);
    }

    public List<SnapshotRow> findSnapshots(Long runId, int limit) {
        return jdbcTemplate.query("SELECT * FROM agent_environment_snapshot WHERE run_id = ? "
                        + "ORDER BY step_no DESC LIMIT ?", SNAPSHOT_ROW_MAPPER, runId, Math.max(1, limit));
    }

    public long insertSnapshot(Long runId, int stepNo, SimulationState state, String sourceType,
                               String inputJson, LocalDateTime createdAt) {
        String sql = "INSERT INTO agent_environment_snapshot (run_id, step_no, simulated_at, temperature_c, "
                + "air_humidity_pct, soil_moisture_pct, co2_ppm, light_ppfd, soil_ph, vpd_kpa, environment_risk, "
                + "disease_pressure, risk_level, source_type, input_json, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        return insertForKey(sql, runId, stepNo, state.getSimulatedAt(), decimal(state.getTemperatureC()),
                decimal(state.getAirHumidityPct()), decimal(state.getSoilMoisturePct()), decimal(state.getCo2Ppm()),
                decimal(state.getLightPpfd()), decimal(state.getSoilPh()), decimal(state.getVpdKpa()),
                decimal(state.getEnvironmentRisk()), decimal(state.getDiseasePressure()), state.getRiskLevel(),
                sourceType, inputJson, createdAt);
    }

    public void replaceSnapshotState(Long snapshotId, SimulationState state) {
        jdbcTemplate.update("UPDATE agent_environment_snapshot SET simulated_at = ?, temperature_c = ?, "
                        + "air_humidity_pct = ?, soil_moisture_pct = ?, co2_ppm = ?, light_ppfd = ?, soil_ph = ?, "
                        + "vpd_kpa = ?, environment_risk = ?, disease_pressure = ?, risk_level = ? WHERE id = ?",
                timestamp(state.getSimulatedAt()), decimal(state.getTemperatureC()), decimal(state.getAirHumidityPct()),
                decimal(state.getSoilMoisturePct()), decimal(state.getCo2Ppm()), decimal(state.getLightPpfd()),
                decimal(state.getSoilPh()), decimal(state.getVpdKpa()), decimal(state.getEnvironmentRisk()),
                decimal(state.getDiseasePressure()), state.getRiskLevel(), snapshotId);
    }

    public List<DeviceRow> findDevices(Long runId) {
        return jdbcTemplate.query("SELECT * FROM agent_device WHERE run_id = ? ORDER BY id", DEVICE_ROW_MAPPER, runId);
    }

    public List<DeviceRow> findDevicesForUpdate(Long runId) {
        return jdbcTemplate.query("SELECT * FROM agent_device WHERE run_id = ? ORDER BY id FOR UPDATE",
                DEVICE_ROW_MAPPER, runId);
    }

    public long insertDevice(DeviceRow row) {
        String sql = "INSERT INTO agent_device (run_id, device_code, device_name, control_mode, health_status, "
                + "desired_state, actual_state, last_changed_step, manual_lock_by, manual_lock_until, legacy_storage_id, "
                + "version, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        return insertForKey(sql, row.runId, row.deviceCode, row.deviceName, row.controlMode, row.healthStatus,
                row.desiredState, row.actualState, row.lastChangedStep, row.manualLockBy, row.manualLockUntil,
                row.legacyStorageId, row.version, row.createdAt, row.updatedAt);
    }

    public void updateDevice(DeviceRow row) {
        jdbcTemplate.update("UPDATE agent_device SET control_mode = ?, health_status = ?, desired_state = ?, "
                        + "actual_state = ?, last_changed_step = ?, manual_lock_by = ?, manual_lock_until = ?, "
                        + "updated_at = ?, version = version + 1 WHERE id = ?",
                row.controlMode, row.healthStatus, row.desiredState, row.actualState, row.lastChangedStep,
                row.manualLockBy, timestamp(row.manualLockUntil), timestamp(row.updatedAt), row.id);
    }

    public void resetDevices(Long runId, LocalDateTime now) {
        jdbcTemplate.update("UPDATE agent_device SET control_mode = 'AUTO', desired_state = 'OFF', actual_state = 'OFF', "
                        + "last_changed_step = 0, manual_lock_by = NULL, manual_lock_until = NULL, updated_at = ?, "
                        + "version = version + 1 WHERE run_id = ?",
                timestamp(now), runId);
    }

    public List<ResourceRow> findResources(Long runId) {
        return jdbcTemplate.query("SELECT * FROM agent_resource_stock WHERE run_id = ? ORDER BY id", RESOURCE_ROW_MAPPER,
                runId);
    }

    public List<ResourceRow> findResourcesForUpdate(Long runId) {
        return jdbcTemplate.query("SELECT * FROM agent_resource_stock WHERE run_id = ? ORDER BY id FOR UPDATE",
                RESOURCE_ROW_MAPPER, runId);
    }

    public long insertResource(ResourceRow row) {
        String sql = "INSERT INTO agent_resource_stock (run_id, resource_code, resource_name, unit, opening_quantity, "
                + "available_quantity, low_threshold, version, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        return insertForKey(sql, row.runId, row.resourceCode, row.resourceName, row.unit, row.openingQuantity,
                row.availableQuantity, row.lowThreshold, row.version, row.createdAt, row.updatedAt);
    }

    public void updateResource(ResourceRow row) {
        jdbcTemplate.update("UPDATE agent_resource_stock SET available_quantity = ?, updated_at = ?, "
                        + "version = version + 1 WHERE id = ?",
                row.availableQuantity, timestamp(row.updatedAt), row.id);
    }

    public void resetResources(Long runId, LocalDateTime now) {
        jdbcTemplate.update("UPDATE agent_resource_stock SET available_quantity = opening_quantity, updated_at = ?, "
                        + "version = version + 1 WHERE run_id = ?", timestamp(now), runId);
    }

    public long insertDecision(Long runId, Long snapshotId, int stepNo, String ruleCode, int priority,
                               String riskLevel, String status, String summary, String reasonJson,
                               String ruleVersion, LocalDateTime createdAt) {
        String sql = "INSERT INTO agent_policy_decision (run_id, snapshot_id, step_no, rule_code, priority, risk_level, "
                + "status, summary, reason_json, rule_version, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        return insertForKey(sql, runId, snapshotId, stepNo, ruleCode, priority, riskLevel, status, summary,
                reasonJson, ruleVersion, createdAt);
    }

    public DecisionRow findLatestDecision(Long runId) {
        return queryOne("SELECT * FROM agent_policy_decision WHERE run_id = ? ORDER BY step_no DESC, id DESC LIMIT 1",
                DECISION_ROW_MAPPER, runId);
    }

    public long insertAction(ActionRow row) {
        String sql = "INSERT INTO agent_device_action (run_id, snapshot_id, decision_id, step_no, device_id, device_code, "
                + "command_id, target_state, execution_status, block_reason, executor_type, actor_username, executed_at, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        return insertForKey(sql, row.runId, row.snapshotId, row.decisionId, row.stepNo, row.deviceId, row.deviceCode,
                row.commandId, row.targetState, row.executionStatus, row.blockReason, row.executorType,
                row.actorUsername, row.executedAt, row.createdAt);
    }

    public void insertLedger(Long runId, Long resourceStockId, Long deviceActionId, BigDecimal changeQuantity,
                             BigDecimal balanceAfter, String reason, LocalDateTime createdAt) {
        jdbcTemplate.update("INSERT INTO agent_resource_ledger (run_id, resource_stock_id, device_action_id, "
                        + "change_quantity, balance_after, reason, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                runId, resourceStockId, deviceActionId, changeQuantity, balanceAfter, reason, timestamp(createdAt));
    }

    public void upsertAlert(Long runId, Long snapshotId, int stepNo, String dedupeKey, String alertCode,
                            String severity, String message, String metadataJson, LocalDateTime now) {
        String sql = "INSERT INTO agent_alert (run_id, snapshot_id, step_no, dedupe_key, alert_code, severity, status, "
                + "message, first_seen_at, last_seen_at, metadata_json) VALUES (?, ?, ?, ?, ?, ?, 'OPEN', ?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE snapshot_id = VALUES(snapshot_id), step_no = VALUES(step_no), "
                + "severity = VALUES(severity), status = 'OPEN', message = VALUES(message), last_seen_at = VALUES(last_seen_at), "
                + "metadata_json = VALUES(metadata_json)";
        jdbcTemplate.update(sql, runId, snapshotId, stepNo, dedupeKey, alertCode, severity, message,
                timestamp(now), timestamp(now), metadataJson);
    }

    public List<AlertRow> findOpenAlerts(Long runId) {
        return jdbcTemplate.query("SELECT * FROM agent_alert WHERE run_id = ? AND status = 'OPEN' "
                        + "ORDER BY last_seen_at DESC, id DESC LIMIT 20", ALERT_ROW_MAPPER, runId);
    }

    public void upsertVisionEvent(Long runId, String sourceType, Long sourceRecordId, LocalDateTime observedAt,
                                  String cropType, Long diseaseId, String detectedLabel, BigDecimal confidence,
                                  String severity, String evidenceUrl, String rawPayload, String reviewStatus,
                                  LocalDateTime createdAt) {
        String sql = "INSERT INTO agent_vision_event (run_id, source_type, source_record_id, observed_at, crop_type, "
                + "disease_id, detected_label, confidence, severity, evidence_url, raw_payload, review_status, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE observed_at = VALUES(observed_at), crop_type = VALUES(crop_type), "
                + "disease_id = VALUES(disease_id), detected_label = VALUES(detected_label), confidence = VALUES(confidence), "
                + "severity = VALUES(severity), evidence_url = VALUES(evidence_url), raw_payload = VALUES(raw_payload), "
                + "review_status = VALUES(review_status)";
        jdbcTemplate.update(sql, runId, sourceType, sourceRecordId, timestamp(observedAt), cropType, diseaseId,
                detectedLabel, confidence, severity, evidenceUrl, rawPayload, reviewStatus, timestamp(createdAt));
    }

    public List<VisionEventRow> findVisionEvents(Long runId, int limit) {
        return jdbcTemplate.query("SELECT * FROM agent_vision_event WHERE run_id = ? ORDER BY observed_at DESC, id DESC LIMIT ?",
                VISION_EVENT_ROW_MAPPER, runId, Math.max(1, limit));
    }

    public List<AuditRow> findAudits(Long runId, int limit) {
        return jdbcTemplate.query("SELECT * FROM agent_audit_log WHERE run_id = ? ORDER BY created_at DESC, id DESC LIMIT ?",
                AUDIT_ROW_MAPPER, runId, Math.max(1, limit));
    }

    public void insertAudit(Long runId, String eventType, String entityType, Long entityId, String actorUsername,
                            String traceId, String beforeJson, String afterJson, LocalDateTime createdAt) {
        jdbcTemplate.update("INSERT INTO agent_audit_log (run_id, event_type, entity_type, entity_id, actor_username, "
                        + "trace_id, before_json, after_json, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                runId, eventType, entityType, entityId, actorUsername, traceId, beforeJson, afterJson,
                timestamp(createdAt));
    }

    public void clearRuntimeData(Long runId) {
        jdbcTemplate.update("DELETE FROM agent_resource_ledger WHERE run_id = ?", runId);
        jdbcTemplate.update("DELETE FROM agent_device_action WHERE run_id = ?", runId);
        jdbcTemplate.update("DELETE FROM agent_policy_decision WHERE run_id = ?", runId);
        jdbcTemplate.update("DELETE FROM agent_alert WHERE run_id = ?", runId);
        jdbcTemplate.update("DELETE FROM agent_environment_snapshot WHERE run_id = ?", runId);
    }

    private long insertForKey(final String sql, final Object... values) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(new PreparedStatementCreator() {
            @Override
            public PreparedStatement createPreparedStatement(Connection connection) throws SQLException {
                PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
                for (int index = 0; index < values.length; index++) {
                    bind(statement, index + 1, values[index]);
                }
                return statement;
            }
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("未获取到数据库生成的主键");
        }
        return key.longValue();
    }

    private void bind(PreparedStatement statement, int index, Object value) throws SQLException {
        if (value instanceof LocalDateTime) {
            statement.setTimestamp(index, timestamp((LocalDateTime) value));
        } else {
            statement.setObject(index, value);
        }
    }

    private <T> T queryOne(String sql, RowMapper<T> mapper, Object... values) {
        List<T> rows = jdbcTemplate.query(sql, mapper, values);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static BigDecimal decimal(double value) {
        return BigDecimal.valueOf(value);
    }

    private static Timestamp timestamp(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }

    private static LocalDateTime localDateTime(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer nullableInteger(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static final RowMapper<RunRow> RUN_ROW_MAPPER = new RowMapper<RunRow>() {
        @Override
        public RunRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            RunRow row = new RunRow();
            row.id = rs.getLong("id");
            row.runCode = rs.getString("run_code");
            row.greenhouseCode = rs.getString("greenhouse_code");
            row.cropType = rs.getString("crop_type");
            row.growthStage = rs.getString("growth_stage");
            row.status = rs.getString("status");
            row.activeSlot = nullableInteger(rs, "active_slot");
            row.stepNo = rs.getInt("step_no");
            row.simulatedAt = localDateTime(rs, "simulated_at");
            row.tickMinutes = rs.getInt("tick_minutes");
            row.seed = rs.getLong("seed");
            row.modelVersion = rs.getString("model_version");
            row.baselineJson = rs.getString("baseline_json");
            row.sourceGreenhouseRecordId = nullableLong(rs, "source_greenhouse_record_id");
            row.createdBy = rs.getString("created_by");
            row.version = rs.getInt("version");
            row.createdAt = localDateTime(rs, "created_at");
            row.updatedAt = localDateTime(rs, "updated_at");
            return row;
        }
    };

    private static final RowMapper<SnapshotRow> SNAPSHOT_ROW_MAPPER = new RowMapper<SnapshotRow>() {
        @Override
        public SnapshotRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            SnapshotRow row = new SnapshotRow();
            row.id = rs.getLong("id");
            row.runId = rs.getLong("run_id");
            row.stepNo = rs.getInt("step_no");
            row.state = new SimulationState(localDateTime(rs, "simulated_at"),
                    rs.getBigDecimal("temperature_c").doubleValue(), rs.getBigDecimal("air_humidity_pct").doubleValue(),
                    rs.getBigDecimal("soil_moisture_pct").doubleValue(), rs.getBigDecimal("co2_ppm").doubleValue(),
                    rs.getBigDecimal("light_ppfd").doubleValue(), rs.getBigDecimal("soil_ph").doubleValue(),
                    rs.getBigDecimal("vpd_kpa").doubleValue(), rs.getBigDecimal("environment_risk").doubleValue(),
                    rs.getBigDecimal("disease_pressure").doubleValue(), rs.getString("risk_level"));
            row.sourceType = rs.getString("source_type");
            row.inputJson = rs.getString("input_json");
            row.createdAt = localDateTime(rs, "created_at");
            return row;
        }
    };

    private static final RowMapper<DeviceRow> DEVICE_ROW_MAPPER = new RowMapper<DeviceRow>() {
        @Override
        public DeviceRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            DeviceRow row = new DeviceRow();
            row.id = rs.getLong("id");
            row.runId = rs.getLong("run_id");
            row.deviceCode = rs.getString("device_code");
            row.deviceName = rs.getString("device_name");
            row.controlMode = rs.getString("control_mode");
            row.healthStatus = rs.getString("health_status");
            row.desiredState = rs.getString("desired_state");
            row.actualState = rs.getString("actual_state");
            row.lastChangedStep = rs.getInt("last_changed_step");
            row.manualLockBy = rs.getString("manual_lock_by");
            row.manualLockUntil = localDateTime(rs, "manual_lock_until");
            row.legacyStorageId = nullableLong(rs, "legacy_storage_id");
            row.version = rs.getInt("version");
            row.createdAt = localDateTime(rs, "created_at");
            row.updatedAt = localDateTime(rs, "updated_at");
            return row;
        }
    };

    private static final RowMapper<ResourceRow> RESOURCE_ROW_MAPPER = new RowMapper<ResourceRow>() {
        @Override
        public ResourceRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            ResourceRow row = new ResourceRow();
            row.id = rs.getLong("id");
            row.runId = rs.getLong("run_id");
            row.resourceCode = rs.getString("resource_code");
            row.resourceName = rs.getString("resource_name");
            row.unit = rs.getString("unit");
            row.openingQuantity = rs.getBigDecimal("opening_quantity");
            row.availableQuantity = rs.getBigDecimal("available_quantity");
            row.lowThreshold = rs.getBigDecimal("low_threshold");
            row.version = rs.getInt("version");
            row.createdAt = localDateTime(rs, "created_at");
            row.updatedAt = localDateTime(rs, "updated_at");
            return row;
        }
    };

    private static final RowMapper<DecisionRow> DECISION_ROW_MAPPER = new RowMapper<DecisionRow>() {
        @Override
        public DecisionRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            DecisionRow row = new DecisionRow();
            row.id = rs.getLong("id");
            row.runId = rs.getLong("run_id");
            row.snapshotId = rs.getLong("snapshot_id");
            row.stepNo = rs.getInt("step_no");
            row.summary = rs.getString("summary");
            row.reasonJson = rs.getString("reason_json");
            row.riskLevel = rs.getString("risk_level");
            return row;
        }
    };

    private static final RowMapper<AlertRow> ALERT_ROW_MAPPER = new RowMapper<AlertRow>() {
        @Override
        public AlertRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            AlertRow row = new AlertRow();
            row.id = rs.getLong("id");
            row.alertCode = rs.getString("alert_code");
            row.severity = rs.getString("severity");
            row.status = rs.getString("status");
            row.message = rs.getString("message");
            row.lastSeenAt = localDateTime(rs, "last_seen_at");
            return row;
        }
    };

    private static final RowMapper<VisionEventRow> VISION_EVENT_ROW_MAPPER = new RowMapper<VisionEventRow>() {
        @Override
        public VisionEventRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            VisionEventRow row = new VisionEventRow();
            row.id = rs.getLong("id");
            row.sourceType = rs.getString("source_type");
            row.sourceRecordId = rs.getLong("source_record_id");
            row.observedAt = localDateTime(rs, "observed_at");
            // crop_type / disease_id 原先没被映射，导致"识别结果的作物与知识库对应条目"读不出来（表里其实有这两列）。
            row.cropType = rs.getString("crop_type");
            Object diseaseId = rs.getObject("disease_id");
            row.diseaseId = diseaseId == null ? null : Long.valueOf(rs.getLong("disease_id"));
            row.detectedLabel = rs.getString("detected_label");
            row.confidence = rs.getBigDecimal("confidence");
            row.severity = rs.getString("severity");
            row.evidenceUrl = rs.getString("evidence_url");
            row.reviewStatus = rs.getString("review_status");
            return row;
        }
    };

    private static final RowMapper<AuditRow> AUDIT_ROW_MAPPER = new RowMapper<AuditRow>() {
        @Override
        public AuditRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            AuditRow row = new AuditRow();
            row.id = rs.getLong("id");
            row.eventType = rs.getString("event_type");
            row.entityType = rs.getString("entity_type");
            row.entityId = nullableLong(rs, "entity_id");
            row.actorUsername = rs.getString("actor_username");
            row.createdAt = localDateTime(rs, "created_at");
            return row;
        }
    };

    private static final RowMapper<GreenhouseSeed> GREENHOUSE_SEED_MAPPER = new RowMapper<GreenhouseSeed>() {
        @Override
        public GreenhouseSeed mapRow(ResultSet rs, int rowNum) throws SQLException {
            GreenhouseSeed seed = new GreenhouseSeed();
            seed.id = rs.getLong("id");
            seed.greenhouseName = rs.getString("greenhouse_name");
            seed.cropType = rs.getString("crop_type");
            seed.temperature = rs.getBigDecimal("temperature");
            seed.airHumidity = nullableInteger(rs, "air_humidity");
            seed.soilHumidity = nullableInteger(rs, "soil_humidity");
            seed.co2Concentration = nullableInteger(rs, "co2_concentration");
            seed.soilPh = rs.getBigDecimal("soil_ph");
            seed.lightIntensity = nullableInteger(rs, "light_intensity");
            return seed;
        }
    };

    public static class RunRow {
        public Long id;
        public String runCode;
        public String greenhouseCode;
        public String cropType;
        public String growthStage;
        public String status;
        public Integer activeSlot;
        public int stepNo;
        public LocalDateTime simulatedAt;
        public int tickMinutes;
        public long seed;
        public String modelVersion;
        public String baselineJson;
        public Long sourceGreenhouseRecordId;
        public String createdBy;
        public int version;
        public LocalDateTime createdAt;
        public LocalDateTime updatedAt;
    }

    public static class SnapshotRow {
        public Long id;
        public Long runId;
        public int stepNo;
        public SimulationState state;
        public String sourceType;
        public String inputJson;
        public LocalDateTime createdAt;
    }

    public static class DeviceRow {
        public Long id;
        public Long runId;
        public String deviceCode;
        public String deviceName;
        public String controlMode;
        public String healthStatus;
        public String desiredState;
        public String actualState;
        public int lastChangedStep;
        public String manualLockBy;
        public LocalDateTime manualLockUntil;
        public Long legacyStorageId;
        public int version;
        public LocalDateTime createdAt;
        public LocalDateTime updatedAt;
    }

    public static class ResourceRow {
        public Long id;
        public Long runId;
        public String resourceCode;
        public String resourceName;
        public String unit;
        public BigDecimal openingQuantity;
        public BigDecimal availableQuantity;
        public BigDecimal lowThreshold;
        public int version;
        public LocalDateTime createdAt;
        public LocalDateTime updatedAt;
    }

    public static class DecisionRow {
        public Long id;
        public Long runId;
        public Long snapshotId;
        public int stepNo;
        public String summary;
        public String reasonJson;
        public String riskLevel;
    }

    public static class ActionRow {
        public Long runId;
        public Long snapshotId;
        public Long decisionId;
        public int stepNo;
        public Long deviceId;
        public String deviceCode;
        public String commandId;
        public String targetState;
        public String executionStatus;
        public String blockReason;
        public String executorType;
        public String actorUsername;
        public LocalDateTime executedAt;
        public LocalDateTime createdAt;
    }

    public static class AlertRow {
        public Long id;
        public String alertCode;
        public String severity;
        public String status;
        public String message;
        public LocalDateTime lastSeenAt;
    }

    public static class VisionEventRow {
        public Long id;
        public String sourceType;
        public Long sourceRecordId;
        public LocalDateTime observedAt;
        public String cropType;
        public Long diseaseId;
        public String detectedLabel;
        public BigDecimal confidence;
        public String severity;
        public String evidenceUrl;
        public String reviewStatus;
    }

    public static class AuditRow {
        public Long id;
        public String eventType;
        public String entityType;
        public Long entityId;
        public String actorUsername;
        public LocalDateTime createdAt;
    }

    public static class GreenhouseSeed {
        public Long id;
        public String greenhouseName;
        public String cropType;
        public BigDecimal temperature;
        public Integer airHumidity;
        public Integer soilHumidity;
        public Integer co2Concentration;
        public BigDecimal soilPh;
        public Integer lightIntensity;
    }
}
