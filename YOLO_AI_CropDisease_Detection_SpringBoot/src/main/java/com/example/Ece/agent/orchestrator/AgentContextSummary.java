package com.example.Ece.agent.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Iterator;
import java.util.Map;

/** Prompt summaries keep the frozen archive intact and avoid duplicating its complete scene. */
public final class AgentContextSummary {
    public static ObjectNode taskFacts(ObjectNode frozen) {
        ObjectNode summary = frozen.objectNode();
        summary.set("task", frozen.path("task").deepCopy());
        summary.set("sourceBoundary", frozen.path("sourceBoundary").deepCopy());
        return bounded(summary);
    }

    public static ObjectNode frame(JsonNode frame) {
        ObjectNode copy = (ObjectNode) frame.deepCopy();
        copy.remove("plants"); copy.remove("updates");
        if (copy.path("scenario").isObject()) {
            ObjectNode scenario = (ObjectNode) copy.path("scenario");
            for (String key : new String[]{"trends", "timeline", "shadowRisk", "withoutIntervention", "assumptions", "parameters"}) scenario.remove(key);
            if (scenario.path("decision").path("plan").isObject()) ((ObjectNode) scenario.path("decision").path("plan")).remove("references");
            // A receipt contains another complete scene. Keep its timestamp and
            // comparison scalars; the current scene and the
            // immutable receipt archive already retain the detailed physiology.
            if (scenario.path("decision").path("feedback").isObject()) {
                ObjectNode feedback = (ObjectNode) scenario.path("decision").path("feedback");
                for (String key : new String[]{"agronomy", "shadowAgronomy", "effects", "withoutIntervention", "risk", "environment", "resources"}) feedback.remove(key);
                feedback.put("detailsSummarized", true);
            }
            if (scenario.path("shadowAgronomy").isObject()) {
                ObjectNode shadow = (ObjectNode) scenario.path("shadowAgronomy");
                ObjectNode comparison = copy.objectNode();
                for (String key : new String[]{"modelVersion", "origin", "calibrated", "measured", "soilMoistureUnit", "soilMoistureVwcPct", "rootWaterL", "waterStressFactor", "rootCondition", "canopyWetnessProxy", "canopyWetMinutes", "wetExposureMinutes", "dryExposureMinutes", "diseaseConditionLevel", "riskLevel"})
                    if (shadow.has(key)) comparison.set(key, shadow.get(key));
                comparison.put("detailsSummarized", true);
                scenario.set("shadowAgronomy", comparison);
            }
            // The original M3 environment above retains its locator; the scenario
            // is explicitly simulated and need not repeat the same column map.
            if (scenario.path("environment").isObject()) ((ObjectNode) scenario.path("environment")).remove("source");
            for (String key : new String[]{"agronomy", "shadowAgronomy"})
                if (scenario.path(key).isObject()) ((ObjectNode) scenario.path(key)).remove("notes");
            for (JsonNode condition : scenario.path("agronomy").path("diseaseConditions"))
                if (condition.isObject()) {
                    ((ObjectNode) condition).remove("referenceUrl");
                    ((ObjectNode) condition).remove("referenceScope");
                }
            if (scenario.path("resources").isObject()) {
                ObjectNode resources = (ObjectNode) scenario.path("resources");
                resources.remove("deviceDutyMinutes"); resources.remove("lastStep");
            }
            if (scenario.path("effects").isArray()) {
                ArrayNode active = copy.arrayNode();
                for (JsonNode effect : scenario.path("effects")) if (!"IDLE".equals(effect.path("status").asText())) active.add(effect);
                scenario.set("effects", active);
            }
        }
        return bounded(copy);
    }

    public static ObjectNode promptData(ObjectNode input) { return bounded(input.deepCopy()); }

    private static ObjectNode bounded(ObjectNode copy) {
        // Leave room for the task instructions and adapter hint below the 12,000-character contract.
        int[] textLimits = {1500, 600, 240, 100};
        int[] arrayLimits = {8, 6, 3, 1};
        for (int i = 0; i < textLimits.length && copy.toString().length() > 10000; i++) {
            shrink(copy, textLimits[i], arrayLimits[i]);
            copy.put("contextTruncated", true);
            copy.put("summaryNote", "仅展示本轮关键数据与近期记录，省略部分不可推测；完整信息保留在任务档案。");
        }
        if (copy.toString().length() > 10000) throw new IllegalArgumentException("本轮农情过于复杂，请减少单次上传的字段后重试。");
        return copy;
    }

    private static void shrink(JsonNode node, int textLimit, int arrayLimit) {
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode value = field.getValue();
                if (value.isTextual() && value.asText().length() > textLimit)
                    object.put(field.getKey(), value.asText().substring(0, textLimit) + "…（摘要省略）");
                else shrink(value, textLimit, arrayLimit);
            }
        } else if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            // Task collections are chronological: keep the latest evidence, actions and reviews.
            while (array.size() > arrayLimit) array.remove(0);
            for (JsonNode item : array) shrink(item, textLimit, arrayLimit);
        }
    }
}
