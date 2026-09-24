package com.example.Ece.agent.service;

import com.example.Ece.agent.dto.ManualDeviceRequest;
import com.example.Ece.agent.engine.TomatoDecisionPolicy;
import com.example.Ece.agent.engine.TomatoSimulationEngine;
import com.example.Ece.agent.model.AgentDeviceCodes;
import com.example.Ece.agent.repository.AgentJdbcRepository;
import com.example.Ece.agent.repository.JdbcKnowledgeChunkRepository;
import com.example.Ece.agent.repository.JdbcVisionClassMapRepository;
import com.example.Ece.mapper.ImgRecordsMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentTwinFrameServiceTest {
    private final AgentJdbcRepository repository = mock(AgentJdbcRepository.class);
    private final TomatoSimulationEngine engine = new TomatoSimulationEngine();
    private final AgentRunService service = new AgentRunService(repository, engine, new TomatoDecisionPolicy(),
            new ObjectMapper(), mock(ImgRecordsMapper.class), mock(JdbcVisionClassMapRepository.class),
            mock(JdbcKnowledgeChunkRepository.class));

    @Test
    void historicalFrameUsesRecordedDeviceAndResourceValuesNotLiveRows() {
        AgentJdbcRepository.RunRow run = new AgentJdbcRepository.RunRow();
        run.id = 42L;
        when(repository.findRun(42L)).thenReturn(run);

        AgentJdbcRepository.SnapshotRow frame = new AgentJdbcRepository.SnapshotRow();
        frame.id = 98L;
        frame.stepNo = 3;
        frame.sourceType = "SIMULATED";
        frame.state = engine.evaluate(LocalDateTime.of(2026, 9, 21, 6, 45),
                26, 70, 52, 720, 330, 6.5);
        frame.inputJson = "{\"twinFrameVersion\":1,\"devices\":[{\"code\":\"EXHAUST_FAN\",\"actualState\":\"ON\"}],"
                + "\"resources\":[{\"code\":\"ENERGY\",\"value\":29.58}]}";
        when(repository.findTwinSnapshots(42L)).thenReturn(Collections.singletonList(frame));

        List<Map<String, Object>> frames = service.getTwinFrames(42L);
        assertEquals(3, frames.get(0).get("stepNo"));
        assertEquals(true, frames.get(0).get("recorded"));
        List<?> devices = (List<?>) frames.get(0).get("devices");
        assertEquals("ON", ((Map<?, ?>) devices.get(0)).get("actualState"));
        verify(repository, never()).findDevices(any());
        verify(repository, never()).findResources(any());
    }

    @Test
    void legacySnapshotIsMarkedIncompleteRatherThanBorrowingCurrentDeviceState() {
        when(repository.findRun(42L)).thenReturn(new AgentJdbcRepository.RunRow());
        AgentJdbcRepository.SnapshotRow old = new AgentJdbcRepository.SnapshotRow();
        old.id = 1L;
        old.sourceType = "LEGACY_HISTORY";
        old.inputJson = "{\"source\":\"greenhouse:77\"}";
        old.state = engine.evaluate(LocalDateTime.of(2026, 9, 21, 6, 0),
                24, 70, 52, 720, 330, 6.5);
        when(repository.findTwinSnapshots(42L)).thenReturn(Collections.singletonList(old));

        Map<String, Object> result = service.getTwinFrames(42L).get(0);
        assertFalse((Boolean) result.get("recorded"));
        assertTrue(((List<?>) result.get("devices")).isEmpty());
    }

    @Test
    void manualPadRequiresActualExhaustAndCannotStartFaultyDevice() {
        when(repository.findRunForUpdate(42L)).thenReturn(new AgentJdbcRepository.RunRow());
        AgentJdbcRepository.DeviceRow pad = device(AgentDeviceCodes.COOLING_PAD, "OFF", "NORMAL");
        AgentJdbcRepository.DeviceRow exhaust = device(AgentDeviceCodes.EXHAUST_FAN, "OFF", "NORMAL");
        when(repository.findDevicesForUpdate(42L)).thenReturn(Arrays.asList(exhaust, pad));
        ManualDeviceRequest request = new ManualDeviceRequest();
        request.setMode("MANUAL");
        request.setEnabled(true);

        assertThrows(IllegalStateException.class,
                () -> service.setManualDevice(42L, AgentDeviceCodes.COOLING_PAD, request));
        pad.healthStatus = "FAULT";
        assertThrows(IllegalStateException.class,
                () -> service.setManualDevice(42L, AgentDeviceCodes.COOLING_PAD, request));
        verify(repository, never()).updateDevice(any());
    }

    @Test
    void recordedStepPersistsActualDevicesAndSeparateWaterAndPowerUse() throws Exception {
        List<AgentJdbcRepository.DeviceRow> devices = setupHotStep(false);

        service.stepRun(42L, "operator");

        Map<String, Object> frame = savedFrame();
        assertEquals("ON", stateOf(frame, AgentDeviceCodes.EXHAUST_FAN));
        assertEquals("ON", stateOf(frame, AgentDeviceCodes.COOLING_PAD));
        assertEquals("OFF", stateOf(frame, AgentDeviceCodes.CO2_SUPPLY));
        assertTrue(resourceUsed(frame, AgentDeviceCodes.COOLING_PAD, "WATER") > 0.0);
        assertTrue(resourceUsed(frame, AgentDeviceCodes.COOLING_PAD, "WATER") < 18.0);
        assertEquals(0.06, resourceUsed(frame, AgentDeviceCodes.COOLING_PAD, "ENERGY"));
        assertEquals("ON", findDevice(devices, AgentDeviceCodes.COOLING_PAD).actualState);
        assertEquals(10, ((Map<?, ?>) frame.get("sensorReadings")).size());
        assertEquals(0.0, ((Number) ((Map<?, ?>) frame.get("sensorReadings")).get("SENSOR_FLOW")).doubleValue());
    }

    @Test
    void failedExhaustBlocksPadAndNeverChargesCoolingWater() throws Exception {
        List<AgentJdbcRepository.DeviceRow> devices = setupHotStep(true);
        findDevice(devices, AgentDeviceCodes.EXHAUST_FAN).actualState = "ON";

        service.stepRun(42L, "operator");

        Map<String, Object> frame = savedFrame();
        assertEquals("OFF", stateOf(frame, AgentDeviceCodes.EXHAUST_FAN));
        assertEquals("OFF", findDevice(devices, AgentDeviceCodes.EXHAUST_FAN).actualState);
        assertEquals("OFF", stateOf(frame, AgentDeviceCodes.COOLING_PAD));
        assertEquals(0.0, resourceUsed(frame, AgentDeviceCodes.COOLING_PAD, "WATER"));
        verify(repository).insertAction(org.mockito.ArgumentMatchers.argThat(action ->
                AgentDeviceCodes.COOLING_PAD.equals(action.deviceCode)
                        && "BLOCKED".equals(action.executionStatus)
                        && "EXHAUST_REQUIRED".equals(action.blockReason)));
    }

    @Test
    void stepBillsIrrigationWaterAndPumpPowerAndRecordsFlow() throws Exception {
        setupHotStep(false);
        AgentJdbcRepository.SnapshotRow previous = new AgentJdbcRepository.SnapshotRow();
        previous.stepNo = 0;
        previous.state = engine.evaluate(LocalDateTime.of(2026, 9, 21, 14, 0),
                35, 55, 40, 500, 800, 6.5);
        when(repository.findLatestSnapshot(42L)).thenReturn(previous);

        service.stepRun(42L, "operator");

        Map<String, Object> frame = savedFrame();
        assertEquals("ON", stateOf(frame, AgentDeviceCodes.IRRIGATION));
        assertEquals(60.0, resourceUsed(frame, AgentDeviceCodes.IRRIGATION, "WATER"));
        assertEquals(0.12, resourceUsed(frame, AgentDeviceCodes.IRRIGATION, "ENERGY"));
        assertEquals(4.0, ((Number) ((Map<?, ?>) frame.get("sensorReadings")).get("SENSOR_FLOW")).doubleValue());
    }

    @Test
    void activeManualCo2IsStoppedWhenAutomaticVentilationStarts() throws Exception {
        List<AgentJdbcRepository.DeviceRow> devices = setupHotStep(false);
        AgentJdbcRepository.DeviceRow co2 = findDevice(devices, AgentDeviceCodes.CO2_SUPPLY);
        co2.actualState = "ON";
        co2.controlMode = "MANUAL";

        service.stepRun(42L, "operator");

        Map<String, Object> frame = savedFrame();
        assertEquals("OFF", stateOf(frame, AgentDeviceCodes.CO2_SUPPLY));
        assertEquals(0.0, resourceUsed(frame, AgentDeviceCodes.CO2_SUPPLY, "CO2"));
        verify(repository).insertAction(org.mockito.ArgumentMatchers.argThat(action ->
                AgentDeviceCodes.CO2_SUPPLY.equals(action.deviceCode)
                        && "SAFETY_INTERLOCK".equals(action.blockReason)));
    }

    @Test
    void refusesToMixTwoModelVersionsWithinOneRun() {
        AgentJdbcRepository.RunRow run = new AgentJdbcRepository.RunRow();
        run.id = 42L;
        run.stepNo = 1;
        run.modelVersion = "tomato-greenhouse-v3";
        when(repository.findRunForUpdate(42L)).thenReturn(run);

        assertThrows(IllegalStateException.class, () -> service.stepRun(42L, "operator"));
        verify(repository, never()).insertSnapshot(any(), anyInt(), any(), anyString(), anyString(), any());
    }

    private List<AgentJdbcRepository.DeviceRow> setupHotStep(boolean failedExhaust) {
        AgentJdbcRepository.RunRow run = new AgentJdbcRepository.RunRow();
        run.id = 42L;
        run.tickMinutes = 15;
        run.seed = 77L;
        run.modelVersion = "tomato-greenhouse-v5";
        when(repository.findRunForUpdate(42L)).thenReturn(run);
        when(repository.findRun(42L)).thenReturn(run);
        AgentJdbcRepository.SnapshotRow previous = new AgentJdbcRepository.SnapshotRow();
        previous.stepNo = 0;
        previous.state = engine.evaluate(LocalDateTime.of(2026, 9, 21, 14, 0),
                35, 55, 55, 500, 800, 6.5);
        when(repository.findLatestSnapshot(42L)).thenReturn(previous);
        List<AgentJdbcRepository.DeviceRow> devices = new ArrayList<>();
        for (String code : AgentDeviceCodes.all()) {
            AgentJdbcRepository.DeviceRow entry = device(code, "OFF", "NORMAL");
            entry.id = (long) devices.size() + 1;
            entry.controlMode = "AUTO";
            devices.add(entry);
        }
        if (failedExhaust) findDevice(devices, AgentDeviceCodes.EXHAUST_FAN).healthStatus = "FAULT";
        when(repository.findDevicesForUpdate(42L)).thenReturn(devices);
        List<AgentJdbcRepository.ResourceRow> resources = new ArrayList<>();
        resources.add(resource("WATER", "1200.000"));
        resources.add(resource("ENERGY", "30.000"));
        resources.add(resource("CO2", "2.000"));
        when(repository.findResourcesForUpdate(42L)).thenReturn(resources);
        when(repository.insertSnapshot(any(), anyInt(), any(), anyString(), anyString(), any())).thenReturn(99L);
        return devices;
    }

    private AgentJdbcRepository.ResourceRow resource(String code, String quantity) {
        AgentJdbcRepository.ResourceRow item = new AgentJdbcRepository.ResourceRow();
        item.resourceCode = code;
        item.availableQuantity = new BigDecimal(quantity);
        return item;
    }

    private AgentJdbcRepository.DeviceRow findDevice(List<AgentJdbcRepository.DeviceRow> devices, String code) {
        for (AgentJdbcRepository.DeviceRow device : devices) {
            if (code.equals(device.deviceCode)) return device;
        }
        throw new AssertionError(code);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> savedFrame() throws Exception {
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(repository).updateSnapshotInput(org.mockito.ArgumentMatchers.eq(99L), json.capture());
        return new ObjectMapper().readValue(json.getValue(), Map.class);
    }

    private String stateOf(Map<String, Object> frame, String code) {
        for (Object item : (List<?>) frame.get("devices")) {
            Map<?, ?> device = (Map<?, ?>) item;
            if (code.equals(device.get("code"))) return (String) device.get("actualState");
        }
        throw new AssertionError(code);
    }

    private double resourceUsed(Map<String, Object> frame, String deviceCode, String resourceCode) {
        for (Object item : (List<?>) frame.get("consumption")) {
            Map<?, ?> usage = (Map<?, ?>) item;
            if (deviceCode.equals(usage.get("deviceCode")) && resourceCode.equals(usage.get("resourceCode"))) {
                return ((Number) usage.get("quantity")).doubleValue();
            }
        }
        return 0;
    }

    private AgentJdbcRepository.DeviceRow device(String code, String state, String health) {
        AgentJdbcRepository.DeviceRow device = new AgentJdbcRepository.DeviceRow();
        device.deviceCode = code;
        device.actualState = state;
        device.healthStatus = health;
        return device;
    }
}
