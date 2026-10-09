package com.example.Ece.agent.task;

import com.example.Ece.agent.m3.M3LiveService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;
import java.util.regex.Pattern;

/** Persistent case facts. Model outputs and manual observations keep their own source labels. */
@Service
public class FarmTaskService {
    private static final Pattern UUID_PATTERN = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern ITEM_PATTERN = Pattern.compile("[A-Za-z0-9_.:-]{1,160}");
    private static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_ITEMS = 200;
    private static final int MAX_CONTEXT_BYTES = 44000;
    private static final int MAX_PARAMETER_CATALOG_BYTES = 256000;
    private static final int MAX_PARAMETER_CATALOGS = 16;
    private final ObjectMapper mapper;
    private final Path root;
    private final ObjectProvider<M3LiveService> liveProvider;

    public FarmTaskService(ObjectMapper mapper, ObjectProvider<M3LiveService> liveProvider,
            @Value("${agent.task.directory:data/farm-tasks}") String directory) {
        this.mapper = mapper;
        this.liveProvider = liveProvider;
        this.root = Paths.get(directory).toAbsolutePath().normalize();
    }

    public synchronized ObjectNode create(JsonNode input) throws IOException {
        requireObject(input);
        ObjectNode task = mapper.createObjectNode();
        task.put("id", UUID.randomUUID().toString());
        task.put("schemaVersion", 1);
        task.put("title", "番茄农情任务");
        task.put("crop", "番茄");
        task.put("question", "");
        task.putNull("simulationRunId");
        task.put("createdAt", now());
        for (String key : new String[]{"evidence", "turns", "actions", "observations"}) task.putArray(key);
        applyPatch(task, input);
        save(task);
        return task.deepCopy();
    }

    public synchronized ObjectNode read(String id) throws IOException {
        Path path = path(id);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("农情任务不存在，请新建任务或选择已有任务。");
        if (Files.size(path) > MAX_FILE_BYTES) throw new IOException("任务文件超过允许大小，无法读取。");
        JsonNode raw = mapper.readTree(Files.readAllBytes(path));
        if (!raw.isObject() || !id.equals(raw.path("id").asText()) || raw.path("schemaVersion").asInt() != 1)
            throw new IOException("任务文件格式不支持或已损坏。");
        for (String key : new String[]{"evidence", "turns", "actions", "observations"})
            if (!raw.path(key).isArray() || raw.path(key).size() > MAX_ITEMS) throw new IOException("任务记录格式不支持。");
        if (raw.has("parameterCatalogs") && (!raw.path("parameterCatalogs").isObject()
                || raw.path("parameterCatalogs").size() > MAX_PARAMETER_CATALOGS)) throw new IOException("任务参数目录格式不支持。");
        if (raw.has("decisionSnapshots") && (!raw.path("decisionSnapshots").isObject()
                || raw.path("decisionSnapshots").size() > MAX_ITEMS)) throw new IOException("任务回执目录格式不支持。");
        return ((ObjectNode) raw).deepCopy();
    }

    public synchronized ObjectNode patch(String id, JsonNode input) throws IOException {
        requireObject(input);
        if (input.has("observations")) throw new IllegalArgumentException("复查记录请逐条追加，不能覆盖已有复查记录。");
        ObjectNode task = read(id);
        applyPatch(task, input);
        save(task);
        return task.deepCopy();
    }

    private void applyPatch(ObjectNode task, JsonNode input) {
        if (input.has("title")) task.put("title", text(input, "title", 180, true));
        if (input.has("crop")) task.put("crop", text(input, "crop", 80, true));
        if (input.has("question")) task.put("question", text(input, "question", 12000, false));
        if (input.has("simulationRunId")) {
            String next = optionalUuid(input.path("simulationRunId"));
            String prior = task.path("simulationRunId").asText("");
            if (!prior.isEmpty() && !prior.equals(next) &&
                    (task.path("turns").size() > 0 || task.path("actions").size() > 0))
                throw new IllegalArgumentException("已有对话或行动的任务不能改绑另一运行，请新建任务以保留来源。");
            if (next == null) task.putNull("simulationRunId"); else task.put("simulationRunId", next);
        }
        if (!task.path("simulationRunId").asText("").isEmpty()) requireTomatoTask(task);
        if (input.has("observations")) {
            JsonNode observations = input.get("observations");
            if (!observations.isArray() || observations.size() > MAX_ITEMS) throw new IllegalArgumentException("复查记录最多200条。");
            ArrayNode accepted = task.putArray("observations");
            for (JsonNode observation : observations) {
                requireObject(observation);
                ObjectNode item = boundedObject(observation, 24000);
                ObjectNode base = itemBase(observation);
                item.put("id", base.path("id").asText());
                item.put("createdAt", now());
                item.put("source", "HUMAN_RECORDED");
                item.put("recordedAt", now());
                accepted.add(item);
            }
        }
    }

    public synchronized ObjectNode addEvidence(String id, JsonNode input) throws IOException {
        requireObject(input);
        ObjectNode item = itemBase(input);
        item.put("type", textOr(input, "type", "NOTE", 50));
        item.put("label", text(input, "label", 500, true));
        item.put("source", textOr(input, "source", "USER_PROVIDED", 500));
        if (input.hasNonNull("imageUrl")) {
            String url = text(input, "imageUrl", 8192, false);
            if (url.startsWith("blob:")) throw new IllegalArgumentException("这张图片尚未上传保存，关闭页面后会失效。请先上传到平台，再加入任务。");
            if (!url.isEmpty() && !(url.startsWith("/") && !url.startsWith("//") || url.startsWith("http://") || url.startsWith("https://")))
                throw new IllegalArgumentException("图片地址须为平台已保存图片或HTTP地址，不能包含本地路径或脚本。");
            item.put("imageUrl", url);
        }
        copyBounded(item, input, "candidates", 24000);
        copyBounded(item, input, "details", 24000);
        item.put("diagnosisConfirmed", false);
        return append(id, "evidence", item);
    }

    public synchronized ObjectNode addTurn(String id, JsonNode input) throws IOException {
        requireObject(input);
        ObjectNode item = itemBase(input);
        String role = text(input, "role", 16, true).toUpperCase(java.util.Locale.ROOT);
        if (!"USER".equals(role) && !"ASSISTANT".equals(role)) throw new IllegalArgumentException("对话角色须为USER或ASSISTANT。");
        item.put("role", role);
        item.put("content", text(input, "content", 80000, true));
        for (String key : new String[]{"requestId", "sessionId", "source", "status"})
            if (input.hasNonNull(key)) item.put(key, text(input, key, 500, false));
        // A multi-tool answer can legitimately cite more than one source family. Keep exact locators.
        copyBounded(item, input, "sources", 64000);
        if (input.has("context")) {
            requireObject(input.get("context"));
            ObjectNode context = boundedObject(input.get("context"), MAX_PARAMETER_CATALOG_BYTES);
            compactParameterCatalog(id, context);
            compactDecision(id, context);
            compactSceneNotes(id, context);
            boundContext(context);
            item.set("context", context);
        }
        return append(id, "turns", item);
    }

    /** Append a review under the same lock as other task edits; never replace concurrent records. */
    public synchronized ObjectNode addObservation(String id, JsonNode input) throws IOException {
        requireObject(input);
        ObjectNode item = boundedObject(input, 24000);
        ObjectNode base = itemBase(input);
        item.put("id", base.path("id").asText());
        item.put("createdAt", now());
        item.put("recordedAt", now());
        item.put("source", "HUMAN_RECORDED");
        if (input.has("note")) item.put("note", text(input, "note", 6000, true));
        ObjectNode task = read(id);
        // An identical submission cannot reset the original review's registration time.
        if (find(task.path("observations"), item.path("id").asText()) != null) return task;
        upsert((ArrayNode) task.path("observations"), item);
        save(task);
        return task.deepCopy();
    }

    public synchronized ObjectNode addAction(String id, JsonNode input) throws IOException {
        requireObject(input);
        ObjectNode item = itemBase(input);
        String type = textOr(input, "type", textOr(input, "kind", "HUMAN", 16), 16).toUpperCase(java.util.Locale.ROOT);
        if (!"HUMAN".equals(type) && !"SIMULATION".equals(type)) throw new IllegalArgumentException("行动类型须为HUMAN或SIMULATION。");
        item.put("type", type);
        item.put("kind", type);
        item.put("key", item.path("id").asText());
        item.put("title", text(input, "title", 500, true));
        for (String key : new String[]{"detail", "reviewCondition", "source", "requestId", "stage", "trigger"})
            if (input.hasNonNull(key)) item.put(key, text(input, key, 6000, false));
        String status = textOr(input, "status", "PENDING", 40).toUpperCase(java.util.Locale.ROOT);
        if ("SIMULATION".equals(type) && !"PENDING".equals(status) && !"PROPOSED".equals(status))
            throw new IllegalArgumentException("设备应用状态由仿真回执登记，不能手动确认执行。");
        validateHumanStatus(status);
        item.put("status", status);
        return append(id, "actions", item);
    }

    public synchronized ObjectNode setActionStatus(String id, String key, JsonNode input) throws IOException {
        validateItemId(key);
        requireObject(input);
        ObjectNode task = read(id);
        for (JsonNode node : task.path("actions")) {
            if (!key.equals(node.path("id").asText())) continue;
            if ("SIMULATION".equals(node.path("type").asText())) throw new IllegalArgumentException("仿真行动请在大棚中应用，状态由执行回执更新。");
            String status = text(input, "status", 40, true).toUpperCase(java.util.Locale.ROOT);
            validateHumanStatus(status);
            ObjectNode action = (ObjectNode) node;
            action.put("status", status);
            action.put("updatedAt", now());
            action.put("source", "HUMAN_RECORDED");
            if (input.hasNonNull("reviewNote")) action.put("reviewNote", text(input, "reviewNote", 6000, false));
            save(task);
            return task.deepCopy();
        }
        throw new IllegalArgumentException("行动不存在，请刷新任务。");
    }

    /** Resolve one explicit case/run. Never fall back to the legacy active engine. */
    public ObjectNode context(String taskId, String runId) throws IOException {
        ObjectNode context = mapper.createObjectNode();
        ObjectNode task = null;
        if (taskId != null && !taskId.trim().isEmpty()) {
            task = read(taskId);
            context.set("task", promptFacts(task));
            String bound = task.path("simulationRunId").asText("");
            if (runId != null && !runId.trim().isEmpty() && !bound.isEmpty() && !bound.equals(runId))
                throw new IllegalArgumentException("任务与运行不匹配，请选择同一大棚任务。");
            if ((runId == null || runId.trim().isEmpty()) && !bound.isEmpty()) runId = bound;
        }
        if (runId != null && !runId.trim().isEmpty()) {
            validateUuid(runId);
            if (task != null) requireTomatoTask(task);
            M3LiveService live = liveProvider.getIfAvailable();
            if (live == null) throw new IOException("关联大棚服务暂不可用。");
            ObjectNode run = live.current(runId, true);
            if (task != null && task.path("simulationRunId").asText("").isEmpty()) {
                ObjectNode binding = mapper.createObjectNode(); binding.put("simulationRunId", runId);
                task = patch(taskId, binding);
                context.set("task", promptFacts(task));
            }
            context.put("simulationRunId", runId);
            for (String key : new String[]{"year", "parameterVersion", "observationsSha256", "cursor", "finished", "playback"})
                if (run.has(key)) context.set(key, run.get(key).deepCopy());
            context.set("current", run.path("current").deepCopy());
            if (context.path("current").isObject()) {
                ObjectNode current = (ObjectNode) context.path("current");
                current.remove("plants"); current.remove("updates");
                if (current.path("scenario").isObject()) {
                    ObjectNode scenario = (ObjectNode) current.path("scenario");
                    scenario.remove("trends"); scenario.remove("timeline");
                }
            }
            if (task != null) {
                task = syncReceipt(taskId, run.path("current").path("scenario"));
                context.set("task", promptFacts(task));
            }
        }
        context.put("sourceBoundary", "用户输入和人工记录为所提供证据；图像类别是候选。M3是历史观测回放；生长估计、事件、设备回执、根区与处理对照是仿真。环境、根区及病害响应参数未完成现场标定；没有未来观测或现场效果保证。");
        compactParameterCatalog(taskId, context);
        compactDecision(taskId, context);
        compactSceneNotes(taskId, context);
        boundContext(context);
        return context;
    }

    /** Full parameter descriptions are immutable task-level catalogs; turns retain values and exact lookup keys. */
    private void compactParameterCatalog(String taskId, ObjectNode context) throws IOException {
        JsonNode scenario = context.path("current").path("scenario");
        JsonNode parameters = scenario.path("parameters");
        if (!scenario.isObject() || !parameters.isArray() || parameters.isEmpty()
                || scenario.has("parameterCatalogReference")) return;
        checkJson(parameters, MAX_PARAMETER_CATALOG_BYTES);
        String hash;
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(parameters));
            StringBuilder hex = new StringBuilder();
            for (byte value : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
            hash = hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IOException("无法登记参数指纹", impossible); }
        if (taskId != null) rememberParameterCatalog(taskId, hash, parameters, context.path("parameterVersion").asText(""), scenario.path("responseModelVersion").asText(""));
        ObjectNode reference = mapper.createObjectNode().put("sha256", hash).put("catalogKey", "parameterCatalogs." + hash)
                .put("entryCount", parameters.size()).put("parameterVersion", context.path("parameterVersion").asText(""))
                .put("responseModelVersion", scenario.path("responseModelVersion").asText(""));
        if (taskId == null) reference.put("origin", "RUN_CHECKPOINT").put("runId", context.path("simulationRunId").asText()).put("cursor", context.path("cursor").asInt());
        ArrayNode compact = mapper.createArrayNode();
        for (JsonNode row : parameters) {
            if (!row.isObject()) throw new IllegalArgumentException("参数目录记录须为对象。");
            ObjectNode value = compact.addObject();
            for (String field : new String[]{"name", "key", "code", "value", "unit", "source", "origin", "calibrated", "admissibleMin", "admissibleMax"})
                if (row.has(field)) value.set(field, row.get(field).deepCopy());
        }
        ((ObjectNode) scenario).set("parameters", compact);
        ((ObjectNode) scenario).set("parameterCatalogReference", reference);
    }

    private synchronized void rememberParameterCatalog(String taskId, String hash, JsonNode entries, String parameterVersion, String responseVersion) throws IOException {
        ObjectNode task = read(taskId);
        ObjectNode catalogs = task.has("parameterCatalogs") ? (ObjectNode) task.path("parameterCatalogs") : task.putObject("parameterCatalogs");
        if (catalogs.has(hash)) return;
        if (catalogs.size() >= MAX_PARAMETER_CATALOGS) throw new IllegalArgumentException("本任务已保存16份不同参数目录，请新建任务保留后续参数版本。");
        ObjectNode catalog = catalogs.putObject(hash);
        catalog.put("sha256", hash).put("capturedAt", now()).put("parameterVersion", parameterVersion).put("responseModelVersion", responseVersion);
        catalog.set("entries", entries.deepCopy());
        save(task);
    }

    /** Freeze the full receipt once; its display summary keeps every measured/estimated scalar. */
    private void compactDecision(String taskId, ObjectNode context) throws IOException {
        JsonNode scene = context.path("current").path("scenario");
        JsonNode original = scene.path("decision");
        if (!scene.isObject() || !original.isObject() || !original.path("feedback").isObject()
                || original.has("snapshotReference")) return;
        checkJson(original, MAX_PARAMETER_CATALOG_BYTES);
        String hash;
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(original));
            StringBuilder hex = new StringBuilder();
            for (byte value : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
            hash = hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IOException("无法登记回执指纹", impossible); }
        if (taskId != null) rememberDecision(taskId, hash, original);
        ObjectNode decision = (ObjectNode) original.deepCopy();
        ObjectNode feedback = (ObjectNode) decision.path("feedback");
        for (String key : new String[]{"agronomy", "shadowAgronomy"})
            if (feedback.path(key).isObject()) ((ObjectNode) feedback.path(key)).remove("notes");
        if (feedback.path("effects").isArray()) for (JsonNode row : feedback.path("effects")) if (row.isObject()) {
            ((ObjectNode) row).remove("mechanism"); ((ObjectNode) row).remove("tradeoff"); ((ObjectNode) row).remove("review");
        }
        ObjectNode reference = decision.putObject("snapshotReference").put("sha256", hash)
                .put("at", original.path("feedback").path("at").asText()).put("fullFeedbackRetained", true);
        if (taskId != null) reference.put("catalogKey", "decisionSnapshots." + hash);
        else reference.put("origin", "RUN_CHECKPOINT").put("runId", context.path("simulationRunId").asText()).put("cursor", context.path("cursor").asInt());
        ((ObjectNode) scene).set("decision", decision);
    }

    private void compactSceneNotes(String taskId, ObjectNode context) throws IOException {
        JsonNode raw = context.path("current").path("scenario");
        if (jsonBytes(context) <= MAX_CONTEXT_BYTES || !raw.isObject() || raw.has("explanationReference")) return;
        ObjectNode scene = (ObjectNode) raw;
        ObjectNode explanations = mapper.createObjectNode().put("kind", "SCENARIO_EXPLANATIONS");
        for (String group : new String[]{"agronomy", "shadowAgronomy"}) if (scene.path(group).isObject() && scene.path(group).has("notes")) {
            explanations.set(group + "Notes", scene.path(group).path("notes").deepCopy());
            ((ObjectNode) scene.path(group)).remove("notes");
        }
        if (scene.has("assumptions")) { explanations.set("assumptions", scene.path("assumptions").deepCopy()); scene.remove("assumptions"); }
        ArrayNode effectNotes = explanations.putArray("effectNotes");
        if (scene.path("effects").isArray()) for (JsonNode rawEffect : scene.path("effects")) if (rawEffect.isObject()) {
            ObjectNode effect = (ObjectNode) rawEffect, note = effectNotes.addObject(); note.set("device", effect.path("device").deepCopy());
            for (String field : new String[]{"mechanism", "tradeoff", "review"}) if (effect.has(field)) { note.set(field, effect.get(field).deepCopy()); effect.remove(field); }
        }
        String hash;
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(explanations));
            StringBuilder hex = new StringBuilder(); for (byte value : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff)); hash = hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IOException("无法登记场景说明指纹", impossible); }
        if (taskId != null) rememberDecision(taskId, hash, explanations);
        ObjectNode reference = scene.putObject("explanationReference").put("sha256", hash).put("fullDescriptionsRetained", true);
        if (taskId != null) reference.put("catalogKey", "decisionSnapshots." + hash);
        else reference.put("origin", "RUN_CHECKPOINT").put("runId", context.path("simulationRunId").asText()).put("cursor", context.path("cursor").asInt());
    }

    private synchronized void rememberDecision(String taskId, String hash, JsonNode receipt) throws IOException {
        ObjectNode task = read(taskId);
        ObjectNode snapshots = task.has("decisionSnapshots") ? (ObjectNode) task.path("decisionSnapshots") : task.putObject("decisionSnapshots");
        if (snapshots.has(hash)) return;
        if (snapshots.size() >= MAX_ITEMS) throw new IllegalArgumentException("本任务已保存200份回执快照，请新建任务保留后续记录。");
        snapshots.set(hash, receipt.deepCopy()); save(task);
    }

    /** Keep all current scalar measurements/estimates; old task text is available by id in the archive. */
    private void boundContext(ObjectNode context) {
        // Keep context safe for one saved turn and a bounded LLM prompt.
        while (jsonBytes(context) > MAX_CONTEXT_BYTES && context.path("task").isObject()) {
            ObjectNode facts = (ObjectNode) context.path("task");
            ArrayNode longest = null;
            for (String key : new String[]{"turns", "evidence", "actions", "observations"}) {
                if (facts.path(key).isArray() && facts.path(key).size() > 1 && (longest == null || facts.path(key).toString().length() > longest.toString().length())) longest = (ArrayNode) facts.path(key);
            }
            if (longest == null) break;
            longest.remove(0); facts.put("contextTruncated", true);
        }
        if (jsonBytes(context) > MAX_CONTEXT_BYTES && context.path("task").isObject()) {
            ObjectNode facts = (ObjectNode) context.path("task");
            ObjectNode references = facts.putObject("archiveRecordReferences");
            for (String key : new String[]{"turns", "evidence", "actions", "observations"}) {
                JsonNode rows = facts.path(key);
                ArrayNode ids = references.putArray(key);
                for (JsonNode row : rows) ids.add(row.path("id").asText());
                if ("turns".equals(key)) facts.remove(key);
                else for (JsonNode row : rows) if (row.isObject()) {
                    ObjectNode item = (ObjectNode) row;
                    for (String field : new String[]{"content", "detail", "note", "candidatesSummary", "detailsSummary"})
                        if (item.has(field)) item.put(field, truncate(item.path(field).asText(), 400));
                }
            }
            facts.put("question", truncate(facts.path("question").asText(), 1000));
            facts.put("contextTruncated", true);
            facts.put("fullTextInTaskArchive", true);
        }
        if (jsonBytes(context) > MAX_CONTEXT_BYTES && context.path("current").path("scenario").isObject()) {
            ObjectNode scenario = (ObjectNode) context.path("current").path("scenario");
            if (scenario.path("effects").isArray()) {
                ArrayNode active = mapper.createArrayNode();
                for (JsonNode effect : scenario.path("effects")) if (!"IDLE".equals(effect.path("status").asText())) active.add(effect.deepCopy());
                scenario.set("effects", active);
            }
            context.put("inactiveEffectDescriptionsOmitted", true);
        }
        if (jsonBytes(context) > MAX_CONTEXT_BYTES && context.path("current").path("scenario").has("parameterCatalogReference")) {
            // The immutable catalog contains these exact values and units; repeating every row is unnecessary.
            ObjectNode scenario = (ObjectNode) context.path("current").path("scenario");
            scenario.remove("parameters"); scenario.put("parameterValuesInCatalog", true);
        }
        checkJson(context, MAX_CONTEXT_BYTES);
    }

    private int jsonBytes(JsonNode node) { return node.toString().getBytes(StandardCharsets.UTF_8).length; }

    /** Bounded facts, no nested old snapshots or complete conversational transcripts. */
    private ObjectNode promptFacts(ObjectNode task) {
        ObjectNode facts = mapper.createObjectNode();
        for (String key : new String[]{"id", "title", "crop", "question", "simulationRunId"}) facts.set(key, task.path(key).deepCopy());
        facts.put("question", truncate(task.path("question").asText(), 3000));
        for (String collection : new String[]{"evidence", "actions", "observations", "turns"}) {
            ArrayNode items = facts.putArray(collection);
            JsonNode list = task.path(collection);
            int count = "turns".equals(collection) ? 4 : 12;
            for (int i = Math.max(0, list.size() - count); i < list.size(); i++) {
                JsonNode source = list.get(i);
                ObjectNode item = items.addObject();
                for (String field : new String[]{"id", "type", "label", "source", "role", "title", "status", "reviewCondition", "content", "detail", "note", "recordedAt"}) {
                    if (source.hasNonNull(field)) item.put(field, truncate(source.path(field).asText(), "content".equals(field) ? 1800 : 600));
                }
                for (String field : new String[]{"candidates", "details"})
                    if (source.has(field)) item.put(field + "Summary", truncate(source.path(field).toString(), 1600));
            }
            if (list.size() > count) facts.put(collection + "OmittedCount", list.size() - count);
        }
        return facts;
    }

    private static String truncate(String value, int max) { return value.length() > max ? value.substring(0, max) + "…（其余内容在任务档案）" : value; }

    /** Keep each AI device receipt once; current completion status must have its matching receipt. */
    public synchronized ObjectNode syncReceipt(String id, JsonNode scenario) throws IOException {
        ObjectNode task = read(id);
        JsonNode decision = scenario.path("decision");
        String receiptTaskId = decision.path("taskId").asText("");
        String scenarioTaskId = scenario.path("farmTaskId").asText("");
        if (!receiptTaskId.isEmpty() && !id.equals(receiptTaskId)
                || receiptTaskId.isEmpty() && !scenarioTaskId.isEmpty() && !id.equals(scenarioTaskId)) return task;
        String requestId = decision.path("requestId").asText("");
        if (requestId.isEmpty()) return task;
        String status = decision.path("status").asText();
        boolean applied = decision.hasNonNull("executedAt") && decision.path("executedDevices").isObject();
        if (!applied && !"BLOCKED".equals(status) && !"PROPOSED".equals(status)) return task;
        ObjectNode item = mapper.createObjectNode();
        String key = "simulation:" + requestId;
        validateItemId(key);
        item.put("id", key); item.put("key", key); item.put("type", "SIMULATION"); item.put("kind", "SIMULATION");
        item.put("requestId", requestId); item.put("title", "大棚虚拟设备方案");
        item.put("source", "SIMULATION_RECEIPT");
        item.put("status", applied ? "APPLIED_SIMULATION" : status);
        item.put("reviewCondition", "观察同一事件下环境与根区变化；病害症状需人工复查，模型风险下降不代表病斑消失。");
        item.put("createdAt", now());
        item.put("updatedAt", now());
        item.set("receipt", decision.deepCopy());
        item.put("modelAt", scenario.path("at").asText());
        item.put("scenarioVersion", scenario.path("version").asLong());
        JsonNode old = find(task.path("actions"), key);
        if (old != null && old.path("receipt").equals(decision)) return task;
        if (old != null && old.hasNonNull("createdAt")) item.set("createdAt", old.get("createdAt"));
        upsert((ArrayNode) task.path("actions"), item);
        save(task);
        return task.deepCopy();
    }

    public ObjectNode refresh(String id) throws IOException {
        ObjectNode task = read(id);
        String runId = task.path("simulationRunId").asText("");
        if (!runId.isEmpty()) {
            try { context(id, runId); task = read(id); }
            catch (IOException unavailable) { task.put("runUnavailableReason", unavailable.getMessage()); }
        }
        return task;
    }

    public String report(String id) throws IOException {
        ObjectNode task = read(id);
        ObjectNode linked = null;
        String unavailable = null;
        try { linked = context(id, null); task = read(id); }
        catch (IOException error) { unavailable = error.getMessage(); }
        StringBuilder out = new StringBuilder("# 农情任务管理报告\n\n");
        StringBuilder appendix = new StringBuilder();
        out.append("- 任务：").append(task.path("title").asText()).append("\n- 作物：").append(task.path("crop").asText());
        out.append("\n- 建立时间：").append(task.path("createdAt").asText()).append("\n- 导出时间：").append(now()).append("（北京时间）\n\n");
        out.append("## 当前问题\n\n").append(task.path("question").asText()).append("\n\n");
        out.append("## 建议、行动与复查条件\n\n");
        if (task.path("actions").isEmpty()) out.append("尚未登记行动；助手建议不能视为已经执行。\n\n");
        else out.append("| 做什么 | 当前进度 | 操作说明 | 何时复查 |\n| --- | --- | --- | --- |\n");
        for (JsonNode action : task.path("actions")) {
            out.append("| ").append(cell(action.path("title").asText())).append(" | ").append(statusLabel(action.path("status").asText()));
            out.append(" | ").append(cell(action.path("detail").asText("见本轮方案"))).append(" | ").append(cell(action.path("reviewCondition").asText("需要补充复查条件"))).append(" |\n");
            if (action.has("receipt")) appendJson(appendix, "仿真方案回执 · " + action.path("requestId").asText(), action.path("receipt"));
        }
        out.append("\n人工登记表示使用者确认完成；“已应用于仿真”只表示虚拟设备执行。\n\n");
        out.append("## 当前变化、效果与代价\n\n");
        if (unavailable != null) out.append("关联运行暂不可用：").append(unavailable).append("。已存方案与回执保留，本次不使用其他运行补充效果。\n\n");
        else if (linked != null && linked.has("current")) {
            JsonNode current = linked.path("current"), scenario = current.path("scenario");
            out.append("模拟时刻：").append(current.path("at").asText()).append("。以下比较当前组合方案与同一事件下的未干预分支，数值均为仿真；不能拆分成单设备的现场疗效。\n\n");
            out.append("| 看什么 | 当前方案 | 同事件未干预 |\n| --- | --- | --- |\n");
            compareRow(out,"棚内温度","°C",scenario.path("environment"),scenario.path("withoutIntervention"),"temperatureC",1);
            compareRow(out,"空气相对湿度","%",scenario.path("environment"),scenario.path("withoutIntervention"),"airHumidityPct",1);
            compareRow(out,"空气VPD","kPa",scenario.path("risk"),scenario.path("shadowRisk"),"vpdKpa",2);
            compareRow(out,"根区体积含水率","%vol",scenario.path("agronomy"),scenario.path("shadowAgronomy"),"soilMoistureVwcPct",2);
            compareRow(out,"根区过湿累计","分钟",scenario.path("agronomy"),scenario.path("shadowAgronomy"),"wetExposureMinutes",0);
            compareRow(out,"根区偏干累计","分钟",scenario.path("agronomy"),scenario.path("shadowAgronomy"),"dryExposureMinutes",0);
            compareRow(out,"作物虚拟株高","cm",scenario.path("growth"),scenario.path("growth"),"plantHeightCm","withoutInterventionHeightCm",1);
            JsonNode resources = scenario.path("resources"), agronomy = scenario.path("agronomy");
            out.append("\n### 累计投入与水量去向\n\n| 项目 | 当前估计 |\n| --- | --- |\n");
            valueRow(out,"滴灌用水",resources,"irrigationWaterL","L",1);
            valueRow(out,"湿帘用水",resources,"coolingWaterL","L",1);
            valueRow(out,"设备耗电",resources,"electricityKwh","kWh",2);
            valueRow(out,"CO₂补充",resources,"co2Kg","kg",3);
            valueRow(out,"根区排水",agronomy,"drainageL","L",1);
            valueRow(out,"根区蒸散",agronomy,"evapotranspirationL","L",1);
            out.append("\n设备容量、根区初值和响应参数采用场景假设；以上不是水表、电表或现场费用记录。\n\n");
            if (scenario.path("effects").isArray()) {
                out.append("### 各项操作为什么有作用\n\n| 操作 | 作用与当前变化 | 代价或限制 | 复查事项 |\n| --- | --- | --- | --- |\n");
                for (JsonNode rawEffect : scenario.path("effects")) {
                    JsonNode effect = reportEffect(task, scenario, rawEffect);
                    if ("IDLE".equals(effect.path("status").asText())) continue;
                    out.append("| ").append(cell(effect.path("title").asText())).append(" | ").append(cell(effect.path("mechanism").asText()+"；"+effect.path("observed").asText()));
                    out.append(" | ").append(cell(effect.path("tradeoff").asText())).append(" | ").append(cell(effect.path("review").asText())).append(" |\n");
                }
                out.append('\n');
            }
            if (agronomy.path("diseaseConditions").isArray()) {
                out.append("### 需要留意的病害条件\n\n");
                for (JsonNode condition : agronomy.path("diseaseConditions")) out.append("- ").append(condition.path("title").asText()).append("：").append(riskLabel(condition.path("level").asText())).append("；").append(condition.path("review").asText()).append("。\n");
                out.append("\n这些提示描述病害适生条件，不能证明感染、病斑面积或治愈率。\n\n");
            }
            appendJson(appendix,"导出时当前状态、参数与来源",current);
        } else out.append("本任务未关联大棚运行；效果需要补充观察记录，不读取默认或旧评测场景。\n\n");
        out.append("## 人工复查结果\n\n");
        if (task.path("observations").isEmpty()) out.append("尚未登记复查。应补拍症状照片，查看叶面、根区与设备情况，并记录是否出现新变化。\n\n");
        for (JsonNode observation : task.path("observations")) {
            String note = observation.path("note").asText(observation.path("content").asText(observation.path("summary").asText("已登记结构化复查数据，详见附录。")));
            out.append("- ").append(observation.path("recordedAt").asText()).append("：").append(note).append("\n");
        }
        appendJson(appendix,"人工复查结构化记录",task.path("observations"));
        if (task.path("parameterCatalogs").isObject())
            for (java.util.Iterator<java.util.Map.Entry<String, JsonNode>> catalogs = task.path("parameterCatalogs").fields(); catalogs.hasNext();) {
                java.util.Map.Entry<String, JsonNode> catalog = catalogs.next();
                appendJson(appendix, "冻结参数目录 · SHA-256 " + catalog.getKey(), catalog.getValue());
            }
        if (task.path("decisionSnapshots").isObject())
            for (java.util.Iterator<java.util.Map.Entry<String, JsonNode>> snapshots = task.path("decisionSnapshots").fields(); snapshots.hasNext();) {
                java.util.Map.Entry<String, JsonNode> snapshot = snapshots.next();
                appendJson(appendix, "冻结场景说明与决策反馈 · SHA-256 " + snapshot.getKey(), snapshot.getValue());
            }
        out.append("\n## 农情与图像证据\n\n");
        if (task.path("evidence").isEmpty()) out.append("尚未登记证据。\n\n");
        for (JsonNode evidence : task.path("evidence")) {
            out.append("### ").append(evidence.path("label").asText()).append("\n\n来源：").append(evidence.path("source").asText()).append("\n\n");
            if (evidence.hasNonNull("imageUrl")) out.append("图片地址：").append(evidence.path("imageUrl").asText()).append("\n\n");
            if (evidence.has("candidates")) { out.append("图像识别为候选证据，需结合症状和复查确认。\n\n"); appendJson(appendix, "候选识别结果 · " + evidence.path("label").asText(), evidence.path("candidates")); }
            if (evidence.has("details")) appendJson(appendix, "用户提供信息 · " + evidence.path("label").asText(), evidence.path("details"));
        }
        out.append("## 助手研判与方案\n\n");
        if (task.path("turns").isEmpty()) out.append("尚未完成助手问答或规划。\n\n");
        for (JsonNode turn : task.path("turns")) {
            out.append("### ").append("ASSISTANT".equals(turn.path("role").asText()) ? "助手建议" : "用户输入").append(" · ").append(turn.path("createdAt").asText()).append("\n\n");
            out.append(turn.path("content").asText()).append("\n\n");
            if (turn.path("sources").isArray()) for (JsonNode source : turn.path("sources")) {
                String title=source.path("title").asText(source.path("sourceName").asText(source.path("label").asText(source.path("source").asText("本轮引用资料"))));
                out.append("- 参考：").append(title);
                if(source.hasNonNull("url"))out.append(" · ").append(source.path("url").asText());
                else if(source.hasNonNull("sourceUrl"))out.append(" · ").append(source.path("sourceUrl").asText());
                out.append('\n');
            }
            if (turn.has("sources")) appendJson(appendix, "本轮引用来源", turn.path("sources"));
            if (turn.has("context")) appendJson(appendix, "本轮冻结上下文", turn.path("context"));
        }
        out.append("## 来源与验证范围\n\n");
        out.append("M3按历史时间模拟观测到达；株高修正不证明设备、水肥或病害响应已通过现场验证。虚拟事件、设备动作和处理对照为模型估计；人工登记只表示使用者确认，未自动核验现场实施。未到达观测不会用于本轮推演。\n\n");
        out.append("根区初值、基质/根深、蒸散、空气混合及设备响应为公开的工程假设；准确参数以当前模型参数说明为准。图像检测属于候选证据，风险下降不等于病害确诊或已治愈。没有实际记录支持时，不生成增产率、节水率或防治有效率。\n");
        out.append("\n## 附录：可追溯的记录与模型参数\n\n<details>\n<summary>展开原始证据、冻结状态和执行回执</summary>\n\n").append(appendix).append("\n</details>\n");
        return out.toString();
    }

    private String cell(String text) { return truncate(text,800).replace("|","\\|").replace('\n',' ').replace('\r',' '); }
    /** Resolve archived explanations for the readable report without changing the frozen scene. */
    private JsonNode reportEffect(ObjectNode task, JsonNode scenario, JsonNode effect) {
        if (!effect.isObject()) return effect;
        String hash = scenario.path("explanationReference").path("sha256").asText("");
        JsonNode notes = task.path("decisionSnapshots").path(hash).path("effectNotes");
        ObjectNode complete = (ObjectNode) effect.deepCopy();
        for (JsonNode note : notes) if (note.path("device").equals(effect.path("device"))) {
            for (String field : new String[]{"mechanism", "tradeoff", "review"})
                if (!complete.has(field) && note.has(field)) complete.set(field, note.get(field).deepCopy());
            break;
        }
        return complete;
    }
    private String riskLabel(String code) { return "HIGH".equals(code)?"需优先复查":"MEDIUM".equals(code)?"需要观察":"当前较低"; }
    private String numberText(JsonNode node,String key,int decimals) { return node.path(key).isNumber()&&Double.isFinite(node.path(key).asDouble())?String.format(java.util.Locale.ROOT,"%."+decimals+"f",node.path(key).asDouble()):"尚未计算"; }
    private void compareRow(StringBuilder out,String title,String unit,JsonNode applied,JsonNode shadow,String key,int decimals) { compareRow(out,title,unit,applied,shadow,key,key,decimals); }
    private void compareRow(StringBuilder out,String title,String unit,JsonNode applied,JsonNode shadow,String key,String shadowKey,int decimals) {
        out.append("| ").append(title).append("（").append(unit).append("） | ").append(numberText(applied,key,decimals)).append(" | ").append(numberText(shadow,shadowKey,decimals)).append(" |\n");
    }
    private void valueRow(StringBuilder out,String title,JsonNode node,String key,String unit,int decimals) { out.append("| ").append(title).append(" | ").append(numberText(node,key,decimals)).append(' ').append(unit).append(" |\n"); }
    private static String now() { return ZonedDateTime.now(ZoneId.of("Asia/Shanghai")).toOffsetDateTime().toString(); }

    private void appendJson(StringBuilder out, String title, JsonNode data) throws IOException {
        out.append("### ").append(title).append("\n\n```json\n").append(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(data)).append("\n```\n\n");
    }

    private String statusLabel(String status) {
        if ("APPLIED_SIMULATION".equals(status)) return "已应用于仿真";
        if ("DONE".equals(status) || "RECORDED".equals(status)) return "人工已登记";
        if ("REVIEWED".equals(status)) return "人工已复查";
        if ("BLOCKED".equals(status)) return "应用受约束阻止";
        return "待处理建议";
    }

    private synchronized ObjectNode append(String id, String collection, ObjectNode item) throws IOException {
        ObjectNode task = read(id);
        ArrayNode list = (ArrayNode) task.path(collection);
        JsonNode previous = find(list, item.path("id").asText());
        // Each submitted id denotes one immutable evidence/turn/action. Retries cannot rewrite history.
        if (previous != null) return task;
        upsert(list, item);
        save(task);
        return task.deepCopy();
    }

    private void upsert(ArrayNode list, ObjectNode item) {
        for (int i = 0; i < list.size(); i++) if (item.path("id").asText().equals(list.get(i).path("id").asText())) { list.set(i, item); return; }
        if (list.size() >= MAX_ITEMS) throw new IllegalArgumentException("单个任务最多保存200条同类记录，请新建任务。");
        list.add(item);
    }

    private JsonNode find(JsonNode list, String id) { for (JsonNode item : list) if (id.equals(item.path("id").asText())) return item; return null; }

    private ObjectNode itemBase(JsonNode input) {
        ObjectNode item = mapper.createObjectNode();
        String id = input.hasNonNull("id") ? text(input, "id", 160, true) : UUID.randomUUID().toString();
        validateItemId(id);
        item.put("id", id); item.put("createdAt", now());
        return item;
    }

    private static void validateHumanStatus(String status) {
        if (!("PENDING".equals(status) || "PROPOSED".equals(status) || "DONE".equals(status) || "RECORDED".equals(status) || "REVIEWED".equals(status)))
            throw new IllegalArgumentException("人工行动状态须为PENDING、DONE或REVIEWED。");
    }

    private Path path(String id) {
        validateUuid(id);
        Path file = root.resolve(id + ".json").normalize();
        if (!file.getParent().equals(root) || Files.isSymbolicLink(file)) throw new IllegalArgumentException("任务路径无效。");
        return file;
    }

    private void save(ObjectNode task) throws IOException {
        task.put("updatedAt", now());
        byte[] bytes = mapper.writeValueAsBytes(task);
        if (bytes.length > MAX_FILE_BYTES) throw new IllegalArgumentException("任务超过2MB，请新建任务保留后续记录。");
        Files.createDirectories(root);
        if (Files.isSymbolicLink(root)) throw new IOException("任务目录不能为符号链接。");
        Path destination = path(task.path("id").asText());
        Path temporary = Files.createTempFile(root, "farm-task-", ".tmp");
        try {
            Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING);
            try { Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }

    public static void validateUuid(String id) { if (id == null || !UUID_PATTERN.matcher(id).matches()) throw new IllegalArgumentException("任务或运行编号必须是有效UUID。"); }
    private static void requireTomatoTask(ObjectNode task) {
        String crop = task.path("crop").asText("").trim();
        if (!crop.contains("番茄") && !"tomato".equalsIgnoreCase(crop))
            throw new IllegalArgumentException("M3大棚仅用于番茄模拟；其他作物请使用独立农情问答，不关联该运行。");
    }
    private static void validateItemId(String id) { if (!ITEM_PATTERN.matcher(id).matches()) throw new IllegalArgumentException("记录编号格式无效。"); }
    private String optionalUuid(JsonNode node) {
        if (node.isNull() || node.asText().trim().isEmpty()) return null;
        String value = node.asText(); validateUuid(value); return value;
    }
    private void requireObject(JsonNode node) { if (node == null || !node.isObject()) throw new IllegalArgumentException("请输入JSON对象。"); }
    private String text(JsonNode node, String key, int max, boolean required) {
        JsonNode value = node.get(key);
        if (value != null && !value.isNull() && !value.isTextual()) throw new IllegalArgumentException(key + "必须为文本。");
        String text = value == null || value.isNull() ? "" : value.asText().trim();
        if (text.length() > max || required && text.isEmpty()) throw new IllegalArgumentException(key + "为空或超过允许长度。");
        return text;
    }
    private String textOr(JsonNode node, String key, String fallback, int max) { return node.hasNonNull(key) ? text(node, key, max, false) : fallback; }
    private ObjectNode boundedObject(JsonNode node, int maxBytes) {
        checkJson(node, maxBytes);
        return ((ObjectNode) node).deepCopy();
    }
    private void copyBounded(ObjectNode target, JsonNode input, String key, int bytes) { if (input.has(key)) { checkJson(input.get(key), bytes); target.set(key, input.get(key).deepCopy()); } }
    private void checkJson(JsonNode node, int maxBytes) {
        checkDepth(node, 0);
        if (node.toString().getBytes(StandardCharsets.UTF_8).length > maxBytes) throw new IllegalArgumentException("记录内容过大，请精简后保存。");
    }
    private void checkDepth(JsonNode node, int depth) {
        if (depth > 12 || node.size() > 2000) throw new IllegalArgumentException("记录嵌套过深或字段过多。");
        if (node.isFloatingPointNumber() && !Double.isFinite(node.asDouble())) throw new IllegalArgumentException("数值须为有限数。");
        if (node.isContainerNode()) for (JsonNode child : node) checkDepth(child, depth + 1);
    }
}
