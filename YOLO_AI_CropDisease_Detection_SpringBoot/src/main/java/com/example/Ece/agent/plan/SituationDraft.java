package com.example.Ece.agent.plan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 三通道录入的**统一回显形状**：一条条字段 + 每个字段的来路 + 缺了什么 + 有冲突的地方。
 *
 * <p>表单、CSV、自然语言三条路都汇到这里再返回给前端，用户改完确认才进推演。
 * 这一步是"自然语言输入"能不能用的关键：它把模型的抽取错误从"直接进推演"
 * 降级为"用户看得见、并可纠正"。</p>
 */
public class SituationDraft {

    /** 单个字段的取值及其来路。 */
    public static class FieldValue {

        private final String key;
        private final String label;
        private final String unit;
        private final Object value;
        private final String source;
        /** 支撑这个取值的原话/原单元格；手填时为空。**没有它就不该相信这个值。** */
        private final String rawText;

        public FieldValue(String key, String label, String unit, Object value,
                          String source, String rawText) {
            this.key = key;
            this.label = label;
            this.unit = unit;
            this.value = value;
            this.source = source;
            this.rawText = rawText;
        }

        public String getKey() { return key; }

        public String getLabel() { return label; }

        public String getUnit() { return unit; }

        public Object getValue() { return value; }

        public String getSource() { return source; }

        public String getRawText() { return rawText; }
    }

    private final AgriSituationInput input;
    private final List<FieldValue> fields;
    private final List<String> missing;
    private final List<String> conflicts;
    private final List<String> notes;

    public SituationDraft(AgriSituationInput input, List<FieldValue> fields, List<String> missing,
                          List<String> conflicts, List<String> notes) {
        this.input = input;
        this.fields = fields == null ? new ArrayList<FieldValue>() : fields;
        this.missing = missing == null ? new ArrayList<String>() : missing;
        this.conflicts = conflicts == null ? new ArrayList<String>() : conflicts;
        this.notes = notes == null ? new ArrayList<String>() : notes;
    }

    /** 从已填好的输入生成回显（手填表单与 CSV 用）。 */
    public static SituationDraft of(AgriSituationInput input, Map<String, String> evidence,
                                    List<String> notes) {
        List<FieldValue> fields = new ArrayList<FieldValue>();
        for (SituationField field : SituationFields.all()) {
            Object value = input.get(field.getKey());
            if (value == null) {
                continue;
            }
            String raw = evidence == null ? null : evidence.get(field.getKey());
            fields.add(new FieldValue(field.getKey(), field.getLabel(), field.getUnit(), value,
                    SituationFields.SOURCE_USER, raw));
        }
        return new SituationDraft(input, fields, input.missingLabels(),
                new ArrayList<String>(), notes);
    }

    public AgriSituationInput getInput() { return input; }

    public List<FieldValue> getFields() { return Collections.unmodifiableList(fields); }

    /** 没有值的字段（中文标签）。 */
    public List<String> getMissing() { return Collections.unmodifiableList(missing); }

    /** 同一字段被提到两次且不一致。**交用户裁决，不替他去重。** */
    public List<String> getConflicts() { return Collections.unmodifiableList(conflicts); }

    public List<String> getNotes() { return Collections.unmodifiableList(notes); }

    /** 转成下发给前端的 JSON 形状。 */
    public Map<String, Object> toPayload() {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> fieldPayloads = new ArrayList<Map<String, Object>>();
        for (FieldValue field : fields) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("key", field.getKey());
            item.put("label", field.getLabel());
            item.put("unit", field.getUnit());
            item.put("value", field.getValue());
            item.put("source", field.getSource());
            item.put("rawText", field.getRawText());
            fieldPayloads.add(item);
        }
        payload.put("fields", fieldPayloads);
        payload.put("missing", missing);
        payload.put("conflicts", conflicts);
        payload.put("notes", notes);
        payload.put("input", input.asMap());
        return payload;
    }
}
