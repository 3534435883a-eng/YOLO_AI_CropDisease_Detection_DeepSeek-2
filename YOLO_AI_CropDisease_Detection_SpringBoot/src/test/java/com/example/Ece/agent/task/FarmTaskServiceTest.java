package com.example.Ece.agent.task;

import com.example.Ece.agent.m3.M3LiveService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FarmTaskServiceTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();
    private FarmTaskService service;
    private M3LiveService live;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        live = mock(M3LiveService.class);
        ObjectProvider<M3LiveService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(live);
        service = new FarmTaskService(mapper, provider, directory.toString());
    }

    private ObjectNode object() { return mapper.createObjectNode(); }
    private String create() throws IOException {
        return service.create(object().put("question", "番茄连续阴雨，叶片有斑，接下来怎样管理？")).path("id").asText();
    }
    private ObjectNode receipt(String taskId, String requestId) {
        ObjectNode scene = object().put("at", "2025-03-06T12:00").put("version", 8).put("farmTaskId", taskId);
        ObjectNode decision = scene.putObject("decision");
        decision.put("requestId", requestId).put("taskId", taskId).put("status", "MITIGATED").put("executedAt", "2025-03-06T11:30");
        decision.putObject("executedDevices").put("CIRCULATION_FAN", 0.5);
        return scene;
    }

    @Test
    void persistsTaskAndReadCopiesCannotMutateStoredFacts() throws Exception {
        String id = create();
        ObjectNode task = service.read(id);
        assertEquals("番茄", task.path("crop").asText());
        assertTrue(task.path("createdAt").asText().endsWith("+08:00"));
        assertTrue(Files.isRegularFile(directory.resolve(id + ".json")));
        task.put("question", "被改写");
        assertTrue(service.read(id).path("question").asText().contains("连续阴雨"));
    }

    @Test
    void keepsImageAsCandidateAndRejectsTemporaryOrScriptUrls() throws Exception {
        String id = create();
        ObjectNode input = object().put("id", "leaf-1").put("label", "病叶照片").put("type", "IMAGE").put("imageUrl", "/files/leaf.jpg").put("diagnosisConfirmed", true);
        input.putArray("candidates").addObject().put("label", "早疫病").put("confidence", 0.86);
        ObjectNode task = service.addEvidence(id, input);
        assertFalse(task.path("evidence").get(0).path("diagnosisConfirmed").asBoolean());
        assertEquals(0.86, task.path("evidence").get(0).path("candidates").get(0).path("confidence").asDouble());
        for (String url : new String[]{"blob:http://localhost/temporary", "javascript:alert(1)", "C:/leaf.jpg", "//external/image.jpg"})
            assertThrows(IllegalArgumentException.class, () -> service.addEvidence(id, input.deepCopy().put("imageUrl", url)));
        assertEquals(1, service.read(id).path("evidence").size());
    }

    @Test
    void repeatedEvidenceAndTurnsKeepFirstArchivedContent() throws Exception {
        String id = create();
        service.addEvidence(id, object().put("id", "leaf").put("label", "首次照片"));
        service.addEvidence(id, object().put("id", "leaf").put("label", "重复请求不同内容"));
        service.addTurn(id, object().put("id", "answer:1").put("role", "ASSISTANT").put("content", "首次研判"));
        ObjectNode task = service.addTurn(id, object().put("id", "answer:1").put("role", "ASSISTANT").put("content", "重复请求不同回答"));
        assertEquals(1, task.path("evidence").size());
        assertEquals("首次照片", task.path("evidence").get(0).path("label").asText());
        assertEquals(1, task.path("turns").size());
        assertEquals("首次研判", task.path("turns").get(0).path("content").asText());
    }

    @Test
    void humanActionCompletionSurvivesDuplicatePlanAndSimulationCannotBeCheckedOff() throws Exception {
        String id = create();
        ObjectNode action = object().put("id", "leaf-review").put("title", "复查新病斑").put("type", "HUMAN");
        service.addAction(id, action);
        service.setActionStatus(id, "leaf-review", object().put("status", "REVIEWED").put("reviewNote", "已补拍"));
        service.addAction(id, action);
        assertEquals("REVIEWED", service.read(id).path("actions").get(0).path("status").asText());
        service.addAction(id, object().put("id", "device-1").put("title", "模拟通风").put("type", "SIMULATION"));
        assertThrows(IllegalArgumentException.class, () -> service.setActionStatus(id, "device-1", object().put("status", "DONE")));
        assertThrows(IllegalArgumentException.class, () -> service.addAction(id, object().put("title", "模拟通风").put("type", "SIMULATION").put("status", "APPLIED_SIMULATION")));
    }

    @Test
    void concurrentHumanReviewsAppendWithoutOverwritingAndAreIdempotent() throws Exception {
        String id = create();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            java.util.List<Future<?>> writes = new java.util.ArrayList<>();
            for (int i = 0; i < 12; i++) {
                final int index = i;
                writes.add(pool.submit(() -> { service.addObservation(id, object().put("id", "review:" + index).put("note", "叶片复查" + index).put("source", "MODEL").put("recordedAt", "2099-01-01")); return null; }));
            }
            for (Future<?> write : writes) write.get(10, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        ObjectNode original = service.read(id);
        ObjectNode first = (ObjectNode) original.path("observations").get(0);
        ObjectNode repeated = service.addObservation(id, object().put("id", first.path("id").asText()).put("note", "不能重写"));
        assertEquals(12, repeated.path("observations").size());
        assertEquals(first, repeated.path("observations").get(0));
        for (com.fasterxml.jackson.databind.JsonNode record : repeated.path("observations")) {
            assertEquals("HUMAN_RECORDED", record.path("source").asText());
            assertTrue(record.path("recordedAt").asText().endsWith("+08:00"));
        }
        ObjectNode overwrite = object(); overwrite.putArray("observations");
        assertThrows(IllegalArgumentException.class, () -> service.patch(id, overwrite));
        assertEquals(12, service.read(id).path("observations").size());
    }

    @Test
    void bindsOnlyMatchingExplicitRunAndFreezesLatestReceiptOnFirstRead() throws Exception {
        String id = create(), runId = UUID.randomUUID().toString(), requestId = UUID.randomUUID().toString();
        ObjectNode run = object().put("runId", runId).put("year", 2025).put("cursor", 4);
        ObjectNode current = run.putObject("current"); current.put("at", "2025-03-06T12:00");
        current.putArray("plants").add("large per-plant field");
        ObjectNode scene = receipt(id, requestId); scene.putArray("trends").add(123); scene.putArray("timeline").add(456);
        current.set("scenario", scene);
        when(live.current(runId, true)).thenReturn(run);
        ObjectNode context = service.context(id, runId);
        assertEquals(runId, service.read(id).path("simulationRunId").asText());
        assertEquals("APPLIED_SIMULATION", context.path("task").path("actions").get(0).path("status").asText());
        assertFalse(context.path("current").has("plants"));
        assertFalse(context.path("current").path("scenario").has("trends"));
        assertEquals(1, service.refresh(id).path("actions").size());
        assertThrows(IllegalArgumentException.class, () -> service.context(id, UUID.randomUUID().toString()));
        assertFalse(run.path("current").path("plants").isMissingNode(), "冻结剪裁不能改写服务端运行对象");
    }

    @Test
    void receiptDoesNotLeakAcrossTasksSharingOneRun() throws Exception {
        String first = create(), second = create();
        ObjectNode scene = receipt(first, UUID.randomUUID().toString());
        assertEquals(1, service.syncReceipt(first, scene).path("actions").size());
        assertEquals(0, service.syncReceipt(second, scene).path("actions").size());
        ((ObjectNode) scene.path("decision")).remove("taskId");
        assertEquals(0, service.syncReceipt(second, scene).path("actions").size());
    }

    @Test
    void proposalBlockedAndAppliedReceiptHaveDistinctStatesAndOnlyAppliedHasExecution() throws Exception {
        String id = create(), requestId = UUID.randomUUID().toString();
        ObjectNode scene = receipt(id, requestId);
        ObjectNode decision = (ObjectNode) scene.path("decision");
        decision.remove("executedAt"); decision.remove("executedDevices"); decision.put("status", "PROPOSED");
        assertEquals("PROPOSED", service.syncReceipt(id, scene).path("actions").get(0).path("status").asText());
        decision.put("status", "BLOCKED");
        assertEquals("BLOCKED", service.syncReceipt(id, scene).path("actions").get(0).path("status").asText());
        ObjectNode applied = service.syncReceipt(id, receipt(id, requestId));
        assertEquals(1, applied.path("actions").size());
        assertEquals("APPLIED_SIMULATION", applied.path("actions").get(0).path("status").asText());
        assertTrue(applied.path("actions").get(0).path("receipt").has("executedAt"));
    }

    @Test
    void archivedTaskCannotBeReboundToAnotherRun() throws Exception {
        String id = create(), runId = UUID.randomUUID().toString();
        service.patch(id, object().put("simulationRunId", runId));
        service.addTurn(id, object().put("role", "USER").put("content", "已关联的提问"));
        assertThrows(IllegalArgumentException.class, () -> service.patch(id, object().put("simulationRunId", UUID.randomUUID().toString())));
        assertThrows(IllegalArgumentException.class, () -> service.patch(id, object().putNull("simulationRunId")));
    }

    @Test
    void independentReportIncludesEvidenceSourcesReviewAndNoInventedRun() throws Exception {
        String id = create();
        service.addEvidence(id, object().put("label", "病叶照片").put("imageUrl", "/files/leaf.jpg").put("source", "USER_UPLOAD"));
        service.addTurn(id, object().put("role", "ASSISTANT").put("content", "建议复核病斑，保持空气流动。"));
        service.addAction(id, object().put("title", "查看叶片").put("detail", "拍近照").put("reviewCondition", "次日查看有无新斑"));
        service.addObservation(id, object().put("note", "人工登记：叶片仍有旧斑"));
        String report = service.report(id);
        assertTrue(report.contains("连续阴雨"));
        assertTrue(report.contains("查看叶片"));
        assertTrue(report.contains("叶片仍有旧斑"));
        assertTrue(report.contains("USER_UPLOAD"));
        assertTrue(report.contains("本任务未关联大棚运行"));
        assertTrue(report.contains("风险下降不等于病害确诊或已治愈"));
        verifyNoInteractions(live);
    }

    @Test
    void missingLinkedRunKeepsArchiveAndReportsUnavailabilityWithoutFallback() throws Exception {
        String id = create(), runId = UUID.randomUUID().toString();
        service.patch(id, object().put("simulationRunId", runId));
        when(live.current(runId, true)).thenThrow(new IOException("运行未找到"));
        assertEquals("运行未找到", service.refresh(id).path("runUnavailableReason").asText());
        assertTrue(service.report(id).contains("本次不使用其他运行补充效果"));
    }

    @Test
    void rejectsPathTraversalInvalidNumbersAndCorruptArchive() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> service.read("../outside"));
        String id = create();
        ObjectNode input = object().put("label", "CSV"); input.putObject("details").put("humidity", Double.NaN);
        assertThrows(IllegalArgumentException.class, () -> service.addEvidence(id, input));
        Files.write(directory.resolve(id + ".json"), "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> service.read(id));
    }

    @Test
    void otherCropTasksCanUseIndependentContextButCannotBindTomatoM3Run() throws Exception {
        String id = service.create(object().put("crop", "玉米").put("question", "玉米叶片发黄")).path("id").asText();
        assertEquals("玉米", service.context(id, null).path("task").path("crop").asText());
        String runId = UUID.randomUUID().toString();
        assertThrows(IllegalArgumentException.class, () -> service.context(id, runId));
        assertThrows(IllegalArgumentException.class, () -> service.patch(id, object().put("simulationRunId", runId)));
        assertThrows(IllegalArgumentException.class, () -> service.create(object().put("crop", "玉米").put("simulationRunId", runId)));
        assertTrue(service.read(id).path("simulationRunId").isNull());
        verifyNoInteractions(live);
    }

    private String repeated(String text, int times) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < times; i++) result.append(text);
        return result.toString();
    }

    @Test
    void realPausedFeedbackCanBeFrozenRepeatedlyWithExactReceiptAndCurrentIndicators() throws Exception {
        ObjectNode run;
        try (java.io.InputStream stream = getClass().getResourceAsStream("/fixtures/m3-paused-feedback.json")) {
            assertNotNull(stream); run = (ObjectNode) mapper.readTree(stream);
        }
        String id = create(), runId = run.path("runId").asText();
        service.patch(id, object().put("simulationRunId", runId));
        ObjectNode scenario = (ObjectNode) run.path("current").path("scenario");
        scenario.put("farmTaskId", id); ((ObjectNode) scenario.path("decision")).put("taskId", id);
        ObjectNode original = run.deepCopy();
        when(live.current(runId, true)).thenReturn(run);
        ObjectNode frozen = service.context(id, runId);
        assertTrue(frozen.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 44000);
        assertEquals(run.path("current").path("at"), frozen.path("current").path("at"));
        ObjectNode expectedAgronomy = (ObjectNode) scenario.path("agronomy").deepCopy(); expectedAgronomy.remove("notes");
        assertEquals(expectedAgronomy, frozen.path("current").path("scenario").path("agronomy"));
        String explanationsHash = frozen.path("current").path("scenario").path("explanationReference").path("sha256").asText();
        assertEquals(scenario.path("agronomy").path("notes"), service.read(id).path("decisionSnapshots").path(explanationsHash).path("agronomyNotes"));
        String hash = frozen.path("current").path("scenario").path("decision").path("snapshotReference").path("sha256").asText();
        assertEquals(scenario.path("decision"), service.read(id).path("decisionSnapshots").path(hash));
        assertEquals(scenario.path("decision").path("feedback").path("temperatureDifferenceC"), frozen.path("current").path("scenario").path("decision").path("feedback").path("temperatureDifferenceC"));
        ObjectNode turn = object().put("id", "real:one").put("role", "ASSISTANT").put("content", "早疫病候选需人工复查。[1]"); turn.set("context", frozen);
        turn.putArray("sources").addObject().put("index", 1).put("sourceName", "可核对资料").put("sourceUrl", "https://example.org/real-source");
        service.addTurn(id, turn); service.addTurn(id, turn.deepCopy().put("id", "real:two"));
        assertEquals(2, service.read(id).path("decisionSnapshots").size());
        assertTrue(service.report(id).contains(hash)); assertTrue(service.report(id).contains("https://example.org/real-source"));
        assertEquals(original, run);
        ObjectNode runOnly = service.context(null, runId);
        assertTrue(runOnly.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 44000);
        assertEquals("RUN_CHECKPOINT", runOnly.path("current").path("scenario").path("decision").path("snapshotReference").path("origin").asText());
    }

    @Test
    void realMultiRoundTaskWithCurrentApiSnapshotRemainsReadable() throws Exception {
        ObjectNode run, task;
        try (java.io.InputStream stream = getClass().getResourceAsStream("/fixtures/m3-paused-feedback.json")) { run = (ObjectNode) mapper.readTree(stream); }
        try (java.io.InputStream stream = getClass().getResourceAsStream("/fixtures/m3-validation-task.json")) { task = (ObjectNode) mapper.readTree(stream); }
        Files.write(directory.resolve(task.path("id").asText() + ".json"), mapper.writeValueAsBytes(task));
        String id = task.path("id").asText(), runId = run.path("runId").asText();
        when(live.current(runId, true)).thenReturn(run);
        ObjectNode frozen = service.context(id, runId);
        assertTrue(frozen.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 44000);
        ObjectNode prompt = com.example.Ece.agent.orchestrator.AgentContextSummary.frame(frozen.path("current"));
        assertTrue(prompt.toString().length() <= 10000);
        assertEquals(frozen.path("current").path("at"), prompt.path("at"));
        assertEquals(frozen.path("current").path("scenario").path("resources").path("electricityKwh"), prompt.path("scenario").path("resources").path("electricityKwh"));
        assertEquals(3, service.refresh(id).path("evidence").size());
        String report = service.report(id);
        assertTrue(report.contains("流程验证记录"));
        String readableReport = report.substring(0, report.indexOf("<details>"));
        for (com.fasterxml.jackson.databind.JsonNode effect : run.path("current").path("scenario").path("effects")) {
            if ("IDLE".equals(effect.path("status").asText())) continue;
            for (String field : new String[]{"mechanism", "tradeoff", "review"}) {
                String explanation = effect.path(field).asText("");
                if (!explanation.isEmpty()) assertTrue(readableReport.contains(explanation.replace("|", "\\|").replace('\n', ' ').replace('\r', ' ')), field);
            }
        }
    }

    private ObjectNode largeContext(String id) {
        ObjectNode context = object().put("parameterVersion", "test-calibration-v1").put("simulationRunId", UUID.randomUUID().toString());
        ObjectNode task = context.putObject("task").put("id", id).put("question", repeated("连续阴雨的番茄叶片有斑，需要复查。", 140));
        for (String collection : new String[]{"turns", "evidence", "actions", "observations"}) {
            ObjectNode row = task.putArray(collection).addObject().put("id", collection + ":previous").put("label", "原始记录");
            row.put("content", repeated("前轮完整说明已保存于任务档案。", 120));
            row.put("detailsSummary", repeated("农情资料含作物和测量来源。", 120));
        }
        ObjectNode current = context.putObject("current").put("at", "2025-03-06T12:00").put("correctedHeightCm", 68.4).put("observedHeightCm", 66.0);
        current.putObject("environment").put("temperatureC", 23.0).put("airHumidityPct", 88.0);
        ObjectNode scenario = current.putObject("scenario").put("responseModelVersion", "test-scenario-v1");
        scenario.putObject("agronomy").put("soilMoistureVwcPct", 30.2).put("waterStressFactor", 0.91).put("measured", false);
        scenario.putObject("resources").put("totalWaterL", 450.0).put("electricityKwh", 11.3);
        com.fasterxml.jackson.databind.node.ArrayNode parameters = scenario.putArray("parameters");
        for (int i = 0; i < 65; i++) parameters.addObject().put("name", "parameter-" + i).put("value", i + 0.25).put("unit", "%vol")
                .put("source", "PROJECT_ENGINEERING_ASSUMPTION").put("calibrated", false).put("admissibleMin", 0).put("admissibleMax", 100)
                .put("scope", repeated("参数适用边界和推演限制须结合番茄与温室核验。", 12))
                .put("referenceScope", repeated("该文献提供机制参考，不能证明设备容量经过现场标定。", 12))
                .put("referenceUrl", "https://example.org/paper-" + i);
        return context;
    }

    @Test
    void largeParameterCatalogIsStoredOnceAndMultiSourceAnswerKeepsTextLocatorsAndCurrentValues() throws Exception {
        String id = create();
        ObjectNode raw = largeContext(id);
        assertTrue(raw.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 48000, "夹具须实际复现旧上下文上限");
        ObjectNode answer = object().put("id", "answer:large").put("role", "ASSISTANT").put("content", "完整回答：先复核病斑，再根据天气安排通风。[1]");
        answer.set("context", raw.deepCopy());
        com.fasterxml.jackson.databind.node.ArrayNode sources = answer.putArray("sources");
        for (int i = 0; i < 12; i++) sources.addObject().put("index", i + 1).put("sourceCode", "paper:" + i)
                .put("sourceId", i + 100).put("chunkNo", i).put("url", "https://example.org/citation-" + i)
                .put("snippet", repeated("这段引用保留文献定位与实际支持的农艺条件。", 45));
        assertTrue(sources.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 32000);
        ObjectNode task = service.addTurn(id, answer);
        ObjectNode frozen = (ObjectNode) task.path("turns").get(0).path("context");
        assertTrue(frozen.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 44000);
        assertEquals(answer.path("content"), task.path("turns").get(0).path("content"));
        assertEquals(sources, task.path("turns").get(0).path("sources"));
        assertEquals(68.4, frozen.path("current").path("correctedHeightCm").asDouble());
        assertEquals(30.2, frozen.path("current").path("scenario").path("agronomy").path("soilMoistureVwcPct").asDouble());
        assertEquals(450.0, frozen.path("current").path("scenario").path("resources").path("totalWaterL").asDouble());
        String hash = frozen.path("current").path("scenario").path("parameterCatalogReference").path("sha256").asText();
        assertTrue(hash.matches("[a-f0-9]{64}"));
        assertEquals(raw.path("current").path("scenario").path("parameters"), task.path("parameterCatalogs").path(hash).path("entries"));
        assertEquals("%vol", frozen.path("current").path("scenario").path("parameters").get(0).path("unit").asText());
        service.addTurn(id, answer.deepCopy().put("id", "answer:next"));
        ObjectNode after = service.read(id);
        assertEquals(1, after.path("parameterCatalogs").size(), "相同参数目录不能按每轮回答重复复制");
        assertEquals(2, after.path("turns").size());
        String report = service.report(id);
        assertTrue(report.contains(hash));
        assertTrue(report.contains("https://example.org/paper-64"));
        assertTrue(report.contains("https://example.org/citation-11"));
        assertTrue(report.contains("完整回答：先复核病斑"));
    }

    @Test
    void changedParameterCatalogGetsNewFingerprintWhileEarlierFrozenValuesRemainTraceable() throws Exception {
        String id = create();
        ObjectNode first = largeContext(id);
        ObjectNode turn = object().put("id", "version:1").put("role", "ASSISTANT").put("content", "第一版回答"); turn.set("context", first);
        ObjectNode task = service.addTurn(id, turn);
        String oldHash = task.path("turns").get(0).path("context").path("current").path("scenario").path("parameterCatalogReference").path("sha256").asText();
        ObjectNode second = first.deepCopy();
        ((ObjectNode) second.path("current").path("scenario").path("parameters").get(0)).put("value", 0.5);
        second.put("parameterVersion", "test-calibration-v2");
        turn.put("id", "version:2").put("content", "第二版回答"); turn.set("context", second);
        ObjectNode updated = service.addTurn(id, turn);
        String newHash = updated.path("turns").get(1).path("context").path("current").path("scenario").path("parameterCatalogReference").path("sha256").asText();
        assertNotEquals(oldHash, newHash);
        assertEquals(2, updated.path("parameterCatalogs").size());
        assertEquals(0.25, updated.path("parameterCatalogs").path(oldHash).path("entries").get(0).path("value").asDouble());
        assertEquals(0.5, updated.path("parameterCatalogs").path(newHash).path("entries").get(0).path("value").asDouble());
    }
}
