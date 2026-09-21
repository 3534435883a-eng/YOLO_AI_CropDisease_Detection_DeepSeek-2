package com.example.Ece.agent.service;

import com.example.Ece.agent.dto.AgentExplanationRequest;
import com.example.Ece.agent.dto.AgentExplanationResponse;
import com.example.Ece.agent.dto.AgentComparisonResponse;
import com.example.Ece.agent.dto.AgentRunResponse;
import com.example.Ece.agent.dto.AgentRunSummaryResponse;
import com.example.Ece.agent.dto.CreateAgentRunRequest;
import com.example.Ece.agent.dto.DeviceHealthRequest;
import com.example.Ece.agent.dto.ManualDeviceRequest;
import com.example.Ece.agent.dto.VisionImportRequest;
import com.example.Ece.agent.engine.TomatoDecisionPolicy;
import com.example.Ece.agent.engine.TomatoSimulationEngine;
import com.example.Ece.agent.model.AgentDeviceCodes;
import com.example.Ece.agent.model.DecisionPlan;
import com.example.Ece.agent.model.DeviceCommand;
import com.example.Ece.agent.model.SimulationState;
import com.example.Ece.agent.repository.AgentJdbcRepository;
import com.example.Ece.entity.ImgRecords;
import com.example.Ece.mapper.ImgRecordsMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Owns the simulated run lifecycle. The rule engine only proposes actions;
 * this service applies constraints and persists the complete tick atomically.
 */
@Service
public class AgentRunService {
    public static final int TOTAL_STEPS = 96;
    public static final int DEFAULT_TICK_MINUTES = 15;
    public static final long DEFAULT_GREENHOUSE_ID = 77L;
    public static final long DEFAULT_SEED = 20260921L;
    private static final String MODEL_VERSION = "tomato-greenhouse-v1";
    private static final String RULE_VERSION = "tomato-policy-v1";
    private static final LocalDateTime SIMULATION_START = LocalDateTime.of(2026, 9, 21, 6, 0);
    private static final DateTimeFormatter INPUT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AgentJdbcRepository repository;
    private final TomatoSimulationEngine simulationEngine;
    private final TomatoDecisionPolicy decisionPolicy;
    private final ObjectMapper objectMapper;
    private final ImgRecordsMapper imgRecordsMapper;

    public AgentRunService(AgentJdbcRepository repository,
                           TomatoSimulationEngine simulationEngine,
                           TomatoDecisionPolicy decisionPolicy,
                           ObjectMapper objectMapper,
                           ImgRecordsMapper imgRecordsMapper) {
        this.repository = repository;
        this.simulationEngine = simulationEngine;
        this.decisionPolicy = decisionPolicy;
        this.objectMapper = objectMapper;
        this.imgRecordsMapper = imgRecordsMapper;
    }

    @Transactional
    public AgentRunResponse createRun(CreateAgentRunRequest request) {
        CreateAgentRunRequest input = request == null ? new CreateAgentRunRequest() : request;
        long greenhouseId = input.getGreenhouseId() == null ? DEFAULT_GREENHOUSE_ID : input.getGreenhouseId();
        AgentJdbcRepository.GreenhouseSeed source = repository.findGreenhouseSeed(greenhouseId);
        if (source == null) {
            source = defaultSeed(greenhouseId);
        }

        LocalDateTime now = LocalDateTime.now();
        repository.deactivateActiveRuns(now);
        long seed = input.getSeed() == null ? DEFAULT_SEED : input.getSeed();
        String cropName = blankToDefault(input.getCropName(), "番茄");
        String cropCode = blankToDefault(input.getCropCode(), "TOMATO");
        String greenhouseCode = "GH-" + String.format(Locale.ROOT, "%02d", source.id == null ? greenhouseId : source.id);
        String runCode = "TOMATO-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
        SimulationState initial = initialState(source);

        AgentJdbcRepository.RunRow row = new AgentJdbcRepository.RunRow();
        row.runCode = runCode;
        row.greenhouseCode = greenhouseCode;
        row.cropType = cropCode;
        row.growthStage = "开花坐果期";
        row.status = "PAUSED";
        row.activeSlot = 1;
        row.stepNo = 0;
        row.simulatedAt = initial.getSimulatedAt();
        row.tickMinutes = DEFAULT_TICK_MINUTES;
        row.seed = seed;
        row.modelVersion = MODEL_VERSION;
        row.baselineJson = stateJson(initial);
        row.sourceGreenhouseRecordId = source.id;
        row.createdBy = blankToDefault(input.getOperatorUsername(), "operator");
        row.version = 0;
        row.createdAt = now;
        row.updatedAt = now;
        row.id = repository.insertRun(row);

        createDevices(row.id, now);
        createResources(row.id, now);
        repository.insertSnapshot(row.id, 0, initial, "LEGACY_HISTORY",
                jsonMap("source", "greenhouse:" + greenhouseId, "label", "历史基线，仅用于仿真初始化"), now);
        repository.insertAudit(row.id, "RUN_CREATED", "RUN", row.id, row.createdBy,
                UUID.randomUUID().toString(), null, stateJson(initial), now);
        return toRunResponse(row);
    }

    public AgentRunResponse getActiveRun() {
        return toRunResponse(repository.findActiveRun());
    }

    public AgentRunSummaryResponse getSummary(Long runId) {
        AgentJdbcRepository.RunRow run = requireRun(runId, false);
        AgentJdbcRepository.SnapshotRow snapshot = repository.findLatestSnapshot(runId);
        return buildSummary(run, snapshot);
    }

    public AgentComparisonResponse getComparison(Long runId) {
        AgentJdbcRepository.RunRow run = requireRun(runId, false);
        AgentJdbcRepository.SnapshotRow strategySnapshot = repository.findLatestSnapshot(runId);
        SimulationState baseline = parseState(run.baselineJson);
        int steps = strategySnapshot == null ? 0 : strategySnapshot.stepNo;
        for (int index = 0; index < steps; index++) {
            baseline = simulationEngine.advance(baseline, Collections.<String, Boolean>emptyMap(),
                    run.tickMinutes, run.seed);
        }
        SimulationState strategy = strategySnapshot == null ? parseState(run.baselineJson) : strategySnapshot.state;
        AgentComparisonResponse response = new AgentComparisonResponse();
        response.setItems(comparisonItems(baseline, strategy));
        response.setBaseline(stateMap(baseline));
        response.setStrategy(stateMap(strategy));
        response.setResources(resourceMaps(repository.findResources(runId)));
        return response;
    }

    @Transactional
    public AgentRunResponse startRun(Long runId, String actor) {
        AgentJdbcRepository.RunRow row = requireRun(runId, true);
        if ("COMPLETED".equals(row.status)) {
            throw new IllegalStateException("本次模拟已完成，请先重置或回放");
        }
        row.status = "RUNNING";
        row.activeSlot = 1;
        row.updatedAt = LocalDateTime.now();
        repository.updateRun(row);
        audit(row, "RUN_STARTED", actor, null, null);
        return toRunResponse(row);
    }

    @Transactional
    public AgentRunResponse pauseRun(Long runId, String actor) {
        AgentJdbcRepository.RunRow row = requireRun(runId, true);
        row.status = "PAUSED";
        row.activeSlot = 1;
        row.updatedAt = LocalDateTime.now();
        repository.updateRun(row);
        audit(row, "RUN_PAUSED", actor, null, null);
        return toRunResponse(row);
    }

    @Transactional
    public AgentRunResponse stepRun(Long runId, String actor) {
        AgentJdbcRepository.RunRow row = requireRun(runId, true);
        advanceLocked(row, actor, false);
        return toRunResponse(repository.findRun(runId));
    }

    @Transactional
    public AgentRunResponse tickActiveRun() {
        AgentJdbcRepository.RunRow row = repository.findActiveRunningRunForUpdate();
        if (row == null) {
            return null;
        }
        advanceLocked(row, "scheduler", true);
        return toRunResponse(repository.findRun(row.id));
    }

    @Transactional
    public AgentRunResponse resetRun(Long runId, String actor) {
        AgentJdbcRepository.RunRow row = requireRun(runId, true);
        resetLocked(row, actor);
        return toRunResponse(repository.findRun(runId));
    }

    @Transactional
    public AgentRunResponse replayRun(Long runId, String actor) {
        AgentJdbcRepository.RunRow row = requireRun(runId, true);
        resetLocked(row, actor);
        row = repository.findRunForUpdate(runId);
        row.status = "RUNNING";
        row.activeSlot = 1;
        row.updatedAt = LocalDateTime.now();
        repository.updateRun(row);
        audit(row, "RUN_REPLAYED", actor, null, null);
        return toRunResponse(row);
    }

    @Transactional
    public AgentRunSummaryResponse setManualDevice(Long runId, String deviceCode,
                                                   ManualDeviceRequest request) {
        AgentJdbcRepository.RunRow run = requireRun(runId, true);
        String normalizedCode = normalizeDeviceCode(deviceCode);
        ManualDeviceRequest input = request == null ? new ManualDeviceRequest() : request;
        String mode = blankToDefault(input.getMode(), "MANUAL").toUpperCase(Locale.ROOT);
        Boolean enabled = input.getEnabled();
        if (enabled == null) {
            enabled = Boolean.FALSE;
        }
        List<AgentJdbcRepository.DeviceRow> devices = repository.findDevicesForUpdate(runId);
        AgentJdbcRepository.DeviceRow device = findDevice(devices, normalizedCode);
        if (device == null) {
            throw new IllegalArgumentException("未知设备: " + deviceCode);
        }
        LocalDateTime now = LocalDateTime.now();
        String actor = blankToDefault(input.getOperatorUsername(), "operator");
        if ("AUTO".equals(mode)) {
            device.controlMode = "AUTO";
            device.manualLockBy = null;
            device.manualLockUntil = null;
        } else if ("MANUAL".equals(mode)) {
            device.controlMode = "MANUAL";
            device.manualLockBy = actor;
            device.manualLockUntil = now.plusHours(24);
        } else {
            throw new IllegalArgumentException("设备模式必须是 AUTO 或 MANUAL");
        }
        device.desiredState = enabled ? "ON" : "OFF";
        device.actualState = enabled ? "ON" : "OFF";
        device.updatedAt = now;
        repository.updateDevice(device);
        repository.insertAudit(runId, "DEVICE_MANUAL_OVERRIDE", "DEVICE", device.id, actor,
                UUID.randomUUID().toString(), null, jsonMap("mode", mode, "enabled", enabled), now);
        return getSummary(runId);
    }

    @Transactional
    public AgentRunSummaryResponse setDeviceHealth(Long runId, String deviceCode, DeviceHealthRequest request) {
        requireRun(runId, true);
        String normalizedCode = normalizeDeviceCode(deviceCode);
        String requested = request == null ? null : request.getHealthStatus();
        String health = blankToDefault(requested, "NORMAL").toUpperCase(Locale.ROOT);
        if (!"NORMAL".equals(health) && !"OFFLINE".equals(health) && !"FAULT".equals(health)) {
            throw new IllegalArgumentException("设备健康状态必须是 NORMAL、OFFLINE 或 FAULT");
        }
        List<AgentJdbcRepository.DeviceRow> devices = repository.findDevicesForUpdate(runId);
        AgentJdbcRepository.DeviceRow device = findDevice(devices, normalizedCode);
        if (device == null) {
            throw new IllegalArgumentException("未知设备: " + deviceCode);
        }
        String actor = blankToDefault(request == null ? null : request.getOperatorUsername(), "operator");
        String before = device.healthStatus;
        device.healthStatus = health;
        if (!"NORMAL".equals(health)) {
            device.actualState = "OFF";
        }
        device.updatedAt = LocalDateTime.now();
        repository.updateDevice(device);
        repository.insertAudit(runId, "DEVICE_HEALTH_CHANGED", "DEVICE", device.id, actor,
                UUID.randomUUID().toString(), jsonMap("healthStatus", before), jsonMap("healthStatus", health),
                device.updatedAt);
        return getSummary(runId);
    }

    @Transactional
    public AgentRunSummaryResponse importVision(Long runId, VisionImportRequest request) {
        requireRun(runId, true);
        if (request == null || request.getSourceRecordId() == null) {
            throw new IllegalArgumentException("必须提供识别记录 ID");
        }
        String sourceType = blankToDefault(request.getSourceType(), "IMG_RECORD").toUpperCase(Locale.ROOT);
        if (!"IMG_RECORD".equals(sourceType)) {
            throw new IllegalArgumentException("一期仅支持图片识别记录导入");
        }
        ImgRecords record = imgRecordsMapper.selectById(request.getSourceRecordId());
        if (record == null) {
            throw new IllegalArgumentException("未找到图片识别记录");
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime observedAt = parseLegacyTime(record.getStartTime(), now);
        String label = blankToDefault(record.getLable(), "未校准标签");
        BigDecimal confidence = parseConfidence(record.getConf(), record.getConfidence());
        String rawPayload = safeJson(jsonMap("id", record.getId(), "kind", record.getKind(), "label", record.getLable(),
                "confidence", record.getConfidence(), "conf", record.getConf(), "inputImg", record.getInputImg(),
                "outImg", record.getOutImg(), "startTime", record.getStartTime()));
        repository.upsertVisionEvent(runId, sourceType, request.getSourceRecordId(), observedAt, "TOMATO", null,
                label, confidence, "REVIEW", blankToDefault(record.getOutImg(), record.getInputImg()), rawPayload,
                "PENDING_REVIEW", now);
        AgentJdbcRepository.SnapshotRow snapshot = repository.findLatestSnapshot(runId);
        int step = snapshot == null ? 0 : snapshot.stepNo;
        Long snapshotId = snapshot == null ? null : snapshot.id;
        repository.upsertAlert(runId, snapshotId, step, "VISION_REVIEW_REQUIRED_" + request.getSourceRecordId(),
                "VISION_REVIEW_REQUIRED", "MEDIUM", "视觉识别结果已导入，标签/置信度未校准，需人工核验",
                jsonMap("sourceType", sourceType, "sourceRecordId", request.getSourceRecordId()), now);
        repository.insertAudit(runId, "VISION_IMPORTED", "VISION_EVENT", request.getSourceRecordId(), "operator",
                UUID.randomUUID().toString(), null, rawPayload, now);
        return getSummary(runId);
    }

    public AgentExplanationResponse explain(Long runId, AgentExplanationRequest request) {
        AgentRunSummaryResponse summary = getSummary(runId);
        AgentRunResponse run = summary.getRun();
        String question = request == null ? null : request.getQuestion();
        StringBuilder content = new StringBuilder();
        content.append("这是基于已保存规则推演的解释，不是实测结论，也不会直接控制设备。\n");
        content.append("当前运行：").append(run == null ? runId : run.getRunCode()).append("；");
        content.append("策略：").append(summary.getStrategySummary()).append("。\n");
        if (summary.getCurrentState() != null) {
            content.append("环境风险 ").append(summary.getCurrentState().get("environmentRisk"))
                    .append("%，病害环境压力 ").append(summary.getCurrentState().get("diseasePressure")).append("%。\n");
        }
        if (question != null && !question.trim().isEmpty()) {
            content.append("针对问题“").append(question.trim()).append("”：请结合告警、资源余量和人工核验结果确认后再执行现场操作。");
        } else {
            content.append("建议先查看告警和资源流水，再决定是否解除人工接管。");
        }
        return new AgentExplanationResponse(content.toString(), "RULE_ENGINE_STRUCTURED_FALLBACK",
                run == null ? String.valueOf(runId) : run.getRunCode());
    }

    private void advanceLocked(AgentJdbcRepository.RunRow run, String actor, boolean scheduler) {
        if (run.stepNo >= TOTAL_STEPS) {
            run.status = "COMPLETED";
            run.updatedAt = LocalDateTime.now();
            repository.updateRun(run);
            return;
        }
        AgentJdbcRepository.SnapshotRow current = repository.findLatestSnapshot(run.id);
        if (current == null) {
            throw new IllegalStateException("运行缺少初始环境快照");
        }
        List<AgentJdbcRepository.DeviceRow> devices = repository.findDevicesForUpdate(run.id);
        List<AgentJdbcRepository.ResourceRow> resources = repository.findResourcesForUpdate(run.id);
        Map<String, BigDecimal> reservedResources = new HashMap<>();
        DecisionPlan plan = decisionPolicy.decide(current.state);
        int nextStep = current.stepNo + 1;
        LocalDateTime now = LocalDateTime.now();
        Map<String, Boolean> effectiveStates = new LinkedHashMap<>();
        List<Map<String, Object>> reasonRows = new ArrayList<>();
        for (DeviceCommand command : plan.getCommands()) {
            AgentJdbcRepository.DeviceRow device = findDevice(devices, command.getDeviceCode());
            if (device == null) {
                continue;
            }
            Map<String, Object> reason = new LinkedHashMap<>();
            reason.put("deviceCode", command.getDeviceCode());
            reason.put("ruleCode", command.getRuleCode());
            reason.put("summary", command.getSummary());
            reason.put("targetOn", command.isTargetOn());
            reasonRows.add(reason);
        }
        SimulationState projected = simulationEngine.advance(current.state, Collections.<String, Boolean>emptyMap(),
                run.tickMinutes, run.seed);
        long snapshotId = repository.insertSnapshot(run.id, nextStep, projected, "SIMULATED",
                safeJson(jsonMap("previousStep", current.stepNo, "modelVersion", run.modelVersion,
                        "scheduler", scheduler)), now);
        long decisionId = repository.insertDecision(run.id, snapshotId, nextStep, "PLAN_" + nextStep, 1,
                plan.getRiskLevel(), "EVALUATED", plan.getSummary(), safeJson(reasonRows), RULE_VERSION, now);

        effectiveStates.clear();
        for (DeviceCommand command : plan.getCommands()) {
            AgentJdbcRepository.DeviceRow device = findDevice(devices, command.getDeviceCode());
            if (device == null) {
                continue;
            }
            String before = device.actualState;
            String status = "EXECUTED";
            String blockReason = null;
            boolean canApply = true;
            if ("MANUAL".equalsIgnoreCase(device.controlMode)) {
                canApply = false;
                status = "BLOCKED";
                blockReason = "MANUAL_LOCK";
            } else if (!"NORMAL".equalsIgnoreCase(device.healthStatus)) {
                canApply = false;
                status = "BLOCKED";
                blockReason = "DEVICE_" + device.healthStatus;
            }
            if (canApply && command.isTargetOn() && command.getResourceCode() != null
                    && !consumeResourcePreview(resources, reservedResources, command.getResourceCode(), command.getResourceAmount())) {
                canApply = false;
                status = "BLOCKED";
                blockReason = "RESOURCE_SHORTAGE_" + command.getResourceCode();
            }
            if (canApply) {
                device.desiredState = command.isTargetOn() ? "ON" : "OFF";
                device.actualState = device.desiredState;
                if (!device.actualState.equals(before)) {
                    device.lastChangedStep = nextStep;
                }
            } else {
                device.desiredState = command.isTargetOn() ? "ON" : "OFF";
            }
            device.updatedAt = now;
            repository.updateDevice(device);
            String commandId = run.id + ":" + nextStep + ":" + command.getDeviceCode();
            AgentJdbcRepository.ActionRow action = new AgentJdbcRepository.ActionRow();
            action.runId = run.id;
            action.snapshotId = snapshotId;
            action.decisionId = decisionId;
            action.stepNo = nextStep;
            action.deviceId = device.id;
            action.deviceCode = device.deviceCode;
            action.commandId = commandId;
            action.targetState = command.isTargetOn() ? "ON" : "OFF";
            action.executionStatus = status;
            action.blockReason = blockReason;
            action.executorType = canApply ? "AUTO" : "RULE_BLOCKED";
            action.actorUsername = actor;
            action.executedAt = now;
            action.createdAt = now;
            long actionId = repository.insertAction(action);
            if (canApply && command.isTargetOn() && command.getResourceCode() != null) {
                BigDecimal reserved = reservedResources.get(command.getResourceCode());
                reservedResources.put(command.getResourceCode(),
                        (reserved == null ? BigDecimal.ZERO : reserved).add(command.getResourceAmount()));
                consumeResource(resources, command.getResourceCode(), command.getResourceAmount(), run.id, actionId,
                        command.getSummary(), now);
            }
            if (!canApply) {
                repository.upsertAlert(run.id, snapshotId, nextStep, "ACTION_BLOCKED_" + command.getDeviceCode(),
                        "ACTION_BLOCKED", "HIGH".equals(plan.getRiskLevel()) ? "HIGH" : "MEDIUM",
                        command.getDeviceCode() + " 动作被阻断：" + blockReason, safeJson(jsonMap("step", nextStep)), now);
            }
            effectiveStates.put(command.getDeviceCode(), "ON".equalsIgnoreCase(device.actualState));
        }
        // Recalculate the projected snapshot with the actual constrained device states.
        projected = simulationEngine.advance(current.state, effectiveStates, run.tickMinutes, run.seed);
        repository.replaceSnapshotState(snapshotId, projected);
        if (projected.getEnvironmentRisk() >= 70.0 || projected.getDiseasePressure() >= 70.0) {
            repository.upsertAlert(run.id, snapshotId, nextStep, "ENVIRONMENT_HIGH_RISK", "ENVIRONMENT_HIGH_RISK",
                    "HIGH", "环境或病害环境压力达到高风险，需要人工确认", safeJson(stateMap(projected)), now);
        }
        run.stepNo = nextStep;
        run.simulatedAt = projected.getSimulatedAt();
        run.updatedAt = now;
        if (nextStep >= TOTAL_STEPS) {
            run.status = "COMPLETED";
        }
        repository.updateRun(run);
        audit(run, "SIMULATION_TICK", actor, snapshotId, stateJson(projected));
    }

    private void resetLocked(AgentJdbcRepository.RunRow row, String actor) {
        repository.clearRuntimeData(row.id);
        LocalDateTime now = LocalDateTime.now();
        repository.resetDevices(row.id, now);
        repository.resetResources(row.id, now);
        SimulationState initial = parseState(row.baselineJson);
        repository.insertSnapshot(row.id, 0, initial, "LEGACY_HISTORY",
                jsonMap("source", "run-baseline", "label", "重置后的固定历史基线"), now);
        row.status = "PAUSED";
        row.activeSlot = 1;
        row.stepNo = 0;
        row.simulatedAt = initial.getSimulatedAt();
        row.updatedAt = now;
        repository.updateRun(row);
        audit(row, "RUN_RESET", actor, null, stateJson(initial));
    }

    private AgentRunSummaryResponse buildSummary(AgentJdbcRepository.RunRow run,
                                                  AgentJdbcRepository.SnapshotRow snapshot) {
        AgentRunSummaryResponse response = new AgentRunSummaryResponse();
        response.setRun(toRunResponse(run));
        SimulationState state = snapshot == null ? parseState(run.baselineJson) : snapshot.state;
        response.setCurrentState(stateMap(state));
        response.setMetrics(metricMaps(state));
        response.setDevices(deviceMaps(repository.findDevices(run.id)));
        response.setResources(resourceMaps(repository.findResources(run.id)));
        response.setAlerts(alertMaps(repository.findOpenAlerts(run.id)));
        AgentJdbcRepository.DecisionRow decision = repository.findLatestDecision(run.id);
        response.setStrategySummary(decision == null ? "等待第一步规则推演" : decision.summary);
        response.setUpdatedAt(run.updatedAt == null ? null : run.updatedAt.toString());
        return response;
    }

    private AgentRunResponse toRunResponse(AgentJdbcRepository.RunRow row) {
        if (row == null) {
            return null;
        }
        AgentRunResponse response = new AgentRunResponse();
        response.setId(row.id);
        response.setRunCode(row.runCode);
        response.setRunName(row.runCode);
        response.setGreenhouseName("8号温室");
        response.setGreenhouseCode(row.greenhouseCode);
        response.setCropName("番茄");
        response.setCropType(row.cropType);
        response.setStatus(row.status);
        response.setCurrentStep(row.stepNo);
        response.setTotalSteps(TOTAL_STEPS);
        response.setProgress((int) Math.round(row.stepNo * 100.0 / TOTAL_STEPS));
        response.setSimulatedAt(row.simulatedAt == null ? null : row.simulatedAt.toString());
        response.setCreatedAt(row.createdAt == null ? null : row.createdAt.toString());
        response.setUpdatedAt(row.updatedAt == null ? null : row.updatedAt.toString());
        return response;
    }

    private List<Map<String, Object>> metricMaps(SimulationState state) {
        List<Map<String, Object>> metrics = new ArrayList<>();
        metrics.add(metric("temperature", "室内温度", state.getTemperatureC(), "C", "SIMULATED"));
        metrics.add(metric("humidity", "空气湿度", state.getAirHumidityPct(), "%", "SIMULATED"));
        metrics.add(metric("soilMoisture", "土壤水分", state.getSoilMoisturePct(), "%", "SIMULATED"));
        metrics.add(metric("co2", "CO2", state.getCo2Ppm(), "ppm", "SIMULATED"));
        metrics.add(metric("light", "光照", state.getLightPpfd(), "PPFD", "SIMULATED"));
        metrics.add(metric("vpd", "VPD", state.getVpdKpa(), "kPa", "CALCULATED"));
        return metrics;
    }

    private Map<String, Object> metric(String code, String label, double value, String unit, String source) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", code);
        result.put("label", label);
        result.put("value", value);
        result.put("unit", unit);
        result.put("source", source);
        return result;
    }

    private Map<String, Object> stateMap(SimulationState state) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("simulatedAt", state.getSimulatedAt() == null ? null : state.getSimulatedAt().toString());
        map.put("temperatureC", state.getTemperatureC());
        map.put("airHumidityPct", state.getAirHumidityPct());
        map.put("soilMoisturePct", state.getSoilMoisturePct());
        map.put("co2Ppm", state.getCo2Ppm());
        map.put("lightPpfd", state.getLightPpfd());
        map.put("soilPh", state.getSoilPh());
        map.put("vpdKpa", state.getVpdKpa());
        map.put("environmentRisk", state.getEnvironmentRisk());
        map.put("diseasePressure", state.getDiseasePressure());
        map.put("riskLevel", state.getRiskLevel());
        map.put("sourceType", "SIMULATED");
        return map;
    }

    private List<Map<String, Object>> deviceMaps(List<AgentJdbcRepository.DeviceRow> rows) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (AgentJdbcRepository.DeviceRow row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.id);
            item.put("code", row.deviceCode);
            item.put("name", row.deviceName);
            item.put("deviceName", row.deviceName);
            item.put("controlMode", row.controlMode);
            item.put("healthStatus", row.healthStatus);
            item.put("desiredState", row.desiredState);
            item.put("actualState", row.actualState);
            item.put("enabled", "ON".equalsIgnoreCase(row.actualState));
            item.put("manualLockBy", row.manualLockBy);
            result.add(item);
        }
        return result;
    }

    private List<Map<String, Object>> resourceMaps(List<AgentJdbcRepository.ResourceRow> rows) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (AgentJdbcRepository.ResourceRow row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.id);
            item.put("code", row.resourceCode);
            item.put("name", row.resourceName);
            item.put("unit", row.unit);
            item.put("value", row.availableQuantity);
            item.put("availableQuantity", row.availableQuantity);
            item.put("openingQuantity", row.openingQuantity);
            item.put("lowThreshold", row.lowThreshold);
            item.put("sourceType", "SIMULATED");
            result.add(item);
        }
        return result;
    }

    private List<Map<String, Object>> alertMaps(List<AgentJdbcRepository.AlertRow> rows) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (AgentJdbcRepository.AlertRow row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.id);
            item.put("code", row.alertCode);
            item.put("severity", row.severity);
            item.put("status", row.status);
            item.put("message", row.message);
            item.put("createdAt", row.lastSeenAt == null ? null : row.lastSeenAt.toString());
            result.add(item);
        }
        return result;
    }

    private List<Map<String, Object>> comparisonItems(SimulationState baseline, SimulationState strategy) {
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(comparison("temperature", "室内温度", baseline.getTemperatureC(), strategy.getTemperatureC(), "C"));
        items.add(comparison("humidity", "空气湿度", baseline.getAirHumidityPct(), strategy.getAirHumidityPct(), "%"));
        items.add(comparison("soilMoisture", "土壤水分", baseline.getSoilMoisturePct(), strategy.getSoilMoisturePct(), "%"));
        items.add(comparison("environmentRisk", "环境风险", baseline.getEnvironmentRisk(), strategy.getEnvironmentRisk(), "%"));
        items.add(comparison("diseasePressure", "病害环境压力", baseline.getDiseasePressure(), strategy.getDiseasePressure(), "%"));
        return items;
    }

    private Map<String, Object> comparison(String code, String label, double baseline, double strategy, String unit) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("code", code);
        item.put("label", label);
        item.put("baseline", baseline);
        item.put("strategy", strategy);
        item.put("change", strategy - baseline);
        item.put("unit", unit);
        return item;
    }

    private void createDevices(Long runId, LocalDateTime now) {
        Map<String, String> names = new LinkedHashMap<>();
        names.put(AgentDeviceCodes.IRRIGATION, "灌溉水泵");
        names.put(AgentDeviceCodes.VENTILATION, "通风风机");
        names.put(AgentDeviceCodes.GROW_LIGHT, "补光灯");
        names.put(AgentDeviceCodes.SHADE, "遮阳帘");
        names.put(AgentDeviceCodes.CO2_SUPPLY, "CO2 补给");
        for (Map.Entry<String, String> entry : names.entrySet()) {
            AgentJdbcRepository.DeviceRow row = new AgentJdbcRepository.DeviceRow();
            row.runId = runId;
            row.deviceCode = entry.getKey();
            row.deviceName = entry.getValue();
            row.controlMode = "AUTO";
            row.healthStatus = "NORMAL";
            row.desiredState = "OFF";
            row.actualState = "OFF";
            row.lastChangedStep = 0;
            row.version = 0;
            row.createdAt = now;
            row.updatedAt = now;
            repository.insertDevice(row);
        }
    }

    private void createResources(Long runId, LocalDateTime now) {
        addResource(runId, "WATER", "灌溉水", "L", "12.000", "2.000", now);
        addResource(runId, "CO2", "CO2 气体", "kg", "2.000", "0.300", now);
        addResource(runId, "ENERGY", "虚拟能源", "kWh", "30.000", "5.000", now);
    }

    private void addResource(Long runId, String code, String name, String unit, String opening, String low,
                             LocalDateTime now) {
        AgentJdbcRepository.ResourceRow row = new AgentJdbcRepository.ResourceRow();
        row.runId = runId;
        row.resourceCode = code;
        row.resourceName = name;
        row.unit = unit;
        row.openingQuantity = new BigDecimal(opening);
        row.availableQuantity = new BigDecimal(opening);
        row.lowThreshold = new BigDecimal(low);
        row.version = 0;
        row.createdAt = now;
        row.updatedAt = now;
        repository.insertResource(row);
    }

    private boolean consumeResourcePreview(List<AgentJdbcRepository.ResourceRow> resources,
                                           Map<String, BigDecimal> reservedResources, String code,
                                           BigDecimal amount) {
        AgentJdbcRepository.ResourceRow resource = findResource(resources, code);
        if (resource == null || amount == null || resource.availableQuantity == null) {
            return false;
        }
        BigDecimal reserved = reservedResources.get(code);
        BigDecimal used = reserved == null ? BigDecimal.ZERO : reserved;
        return resource.availableQuantity.subtract(used).compareTo(amount) >= 0;
    }

    private void consumeResource(List<AgentJdbcRepository.ResourceRow> resources, String code, BigDecimal amount,
                                 Long runId, Long actionId, String reason, LocalDateTime now) {
        AgentJdbcRepository.ResourceRow resource = findResource(resources, code);
        if (resource == null || amount == null) {
            return;
        }
        resource.availableQuantity = resource.availableQuantity.subtract(amount);
        resource.updatedAt = now;
        repository.updateResource(resource);
        repository.insertLedger(runId, resource.id, actionId, amount.negate(), resource.availableQuantity,
                reason, now);
    }

    private AgentJdbcRepository.RunRow requireRun(Long runId, boolean forUpdate) {
        if (runId == null) {
            throw new IllegalArgumentException("运行 ID 不能为空");
        }
        AgentJdbcRepository.RunRow row = forUpdate ? repository.findRunForUpdate(runId) : repository.findRun(runId);
        if (row == null) {
            throw new IllegalArgumentException("未找到模拟运行: " + runId);
        }
        return row;
    }

    private AgentJdbcRepository.DeviceRow findDevice(List<AgentJdbcRepository.DeviceRow> rows, String code) {
        for (AgentJdbcRepository.DeviceRow row : rows) {
            if (code.equals(row.deviceCode)) {
                return row;
            }
        }
        return null;
    }

    private AgentJdbcRepository.ResourceRow findResource(List<AgentJdbcRepository.ResourceRow> rows, String code) {
        for (AgentJdbcRepository.ResourceRow row : rows) {
            if (code.equals(row.resourceCode)) {
                return row;
            }
        }
        return null;
    }

    private SimulationState initialState(AgentJdbcRepository.GreenhouseSeed seed) {
        return simulationEngine.evaluate(SIMULATION_START, number(seed.temperature, 24.0),
                number(seed.airHumidity, 75.0), number(seed.soilHumidity, 40.0),
                number(seed.co2Concentration, 720.0), number(seed.lightIntensity, 310.0),
                number(seed.soilPh, 6.8));
    }

    private AgentJdbcRepository.GreenhouseSeed defaultSeed(long id) {
        AgentJdbcRepository.GreenhouseSeed seed = new AgentJdbcRepository.GreenhouseSeed();
        seed.id = id;
        seed.greenhouseName = "8号温室";
        seed.cropType = "番茄";
        seed.temperature = new BigDecimal("24.0");
        seed.airHumidity = 75;
        seed.soilHumidity = 40;
        seed.co2Concentration = 720;
        seed.soilPh = new BigDecimal("6.8");
        seed.lightIntensity = 310;
        return seed;
    }

    private double number(BigDecimal value, double fallback) {
        return value == null ? fallback : value.doubleValue();
    }

    private double number(Integer value, double fallback) {
        return value == null ? fallback : value.doubleValue();
    }

    private String normalizeDeviceCode(String code) {
        String normalized = blankToDefault(code, "").toUpperCase(Locale.ROOT);
        if ("GROW_LIGHT".equals(normalized)) {
            return AgentDeviceCodes.GROW_LIGHT;
        }
        if (!AgentDeviceCodes.all().contains(normalized)) {
            throw new IllegalArgumentException("未知设备: " + code);
        }
        return normalized;
    }

    private void audit(AgentJdbcRepository.RunRow row, String eventType, String actor, Long entityId, String after) {
        repository.insertAudit(row.id, eventType, "RUN", entityId == null ? row.id : entityId,
                blankToDefault(actor, "operator"), UUID.randomUUID().toString(), null, after, LocalDateTime.now());
    }

    private String stateJson(SimulationState state) {
        return safeJson(stateMap(state));
    }

    private String jsonMap(Object... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            map.put(String.valueOf(values[index]), values[index + 1]);
        }
        return safeJson(map);
    }

    private String safeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            return "{}";
        }
    }

    private SimulationState parseState(String json) {
        try {
            Map<String, Object> map = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() { });
            LocalDateTime at = LocalDateTime.parse(String.valueOf(map.get("simulatedAt")));
            return simulationEngine.evaluate(at, number(map.get("temperatureC"), 24.0),
                    number(map.get("airHumidityPct"), 75.0), number(map.get("soilMoisturePct"), 40.0),
                    number(map.get("co2Ppm"), 720.0), number(map.get("lightPpfd"), 310.0),
                    number(map.get("soilPh"), 6.8));
        } catch (Exception error) {
            return simulationEngine.evaluate(SIMULATION_START, 24.0, 75.0, 40.0, 720.0, 310.0, 6.8);
        }
    }

    private double number(Object value, double fallback) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return value == null ? fallback : Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

    private BigDecimal parseConfidence(String primary, String secondary) {
        String value = blankToDefault(primary, secondary);
        if (value == null) {
            return null;
        }
        String cleaned = value.replaceAll("[^0-9.\\-]", "");
        if (cleaned.isEmpty()) {
            return null;
        }
        try {
            BigDecimal result = new BigDecimal(cleaned);
            if (result.compareTo(BigDecimal.ONE) > 0 && result.compareTo(new BigDecimal("100")) <= 0) {
                result = result.divide(new BigDecimal("100"));
            }
            return result.max(BigDecimal.ZERO).min(BigDecimal.ONE);
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private LocalDateTime parseLegacyTime(String value, LocalDateTime fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        try {
            return LocalDateTime.parse(value.trim(), INPUT_TIME);
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(value.trim());
            } catch (DateTimeParseException ignoredAgain) {
                return fallback;
            }
        }
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }
}
