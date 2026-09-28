package com.example.Ece.agent.plan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 结构化农情输入。表单、CSV 上传、自然语言抽取三条入口最终都汇成这一种形状。
 *
 * <p><b>为什么用 Map 而不是一堆具名字段</b>：字段定义在 {@link SituationFields} 里是一张表，
 * 这里若再写二十个具名属性，就等于把同一张表抄了第二遍——加字段时必有一处漏改。
 * 用 Map 让"定义"与"取值"天然对齐，取值经 {@link #number} / {@link #text} 做类型归一。</p>
 *
 * <p><b>没有值就是没有值</b>：不提供任何"取默认值"的方法。缺字段由
 * {@link #missingFields()} 如实列出，交界面显示与推演提示词使用。
 * 给一个"典型值"当默认，就是把未知伪装成已知——这条链路上最不该做的事。</p>
 */
public class AgriSituationInput {

    private final Map<String, Object> values = new LinkedHashMap<String, Object>();

    public AgriSituationInput() {
    }

    public static AgriSituationInput fromMap(Map<String, Object> raw) {
        AgriSituationInput input = new AgriSituationInput();
        if (raw != null) {
            for (Map.Entry<String, Object> entry : raw.entrySet()) {
                input.put(entry.getKey(), entry.getValue());
            }
        }
        return input;
    }

    /**
     * 写入一个字段。未登记的 key 直接忽略——宁可少一个字段，也不要一个没有定义、
     * 因而在提示词与校验里都不存在的幽灵字段。
     */
    public void put(String key, Object value) {
        if (SituationFields.byKey(key) == null) {
            return;
        }
        Object normalized = normalize(key, value);
        if (normalized == null) {
            values.remove(key);
        } else {
            values.put(key, normalized);
        }
    }

    /** 原始取值；无值返回 {@code null}。 */
    public Object get(String key) {
        return values.get(key);
    }

    /** 文本取值；无值返回 {@code null}。 */
    public String text(String key) {
        Object value = values.get(key);
        return value == null ? null : String.valueOf(value);
    }

    /** 数值取值；无值或不是数值返回 {@code null}。 */
    public Double number(String key) {
        Object value = values.get(key);
        return value instanceof Number ? Double.valueOf(((Number) value).doubleValue()) : null;
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    /** 有值的字段数。 */
    public int size() {
        return values.size();
    }

    /** 值的有序快照（插入顺序即 {@link SituationFields} 的登记顺序时最易读）。 */
    public Map<String, Object> asMap() {
        return Collections.unmodifiableMap(new LinkedHashMap<String, Object>(values));
    }

    /** 没有值的字段 key，按登记顺序。如实报缺，不补默认值。 */
    public List<String> missingFields() {
        List<String> missing = new ArrayList<String>();
        for (SituationField field : SituationFields.all()) {
            if (!values.containsKey(field.getKey())) {
                missing.add(field.getKey());
            }
        }
        return missing;
    }

    /** 缺失字段的中文标签，供文案直接使用。 */
    public List<String> missingLabels() {
        List<String> labels = new ArrayList<String>();
        for (String key : missingFields()) {
            SituationField field = SituationFields.byKey(key);
            labels.add(field == null ? key : field.getLabel());
        }
        return labels;
    }

    /**
     * 渲染成提示词里的一段"已知农情 + 明确缺失"。
     *
     * <p>缺失项**要写进提示词**，不能只写已知项：模型看不到"没给什么"时，会默认那些量是
     * 正常的，进而给出没有针对性的方案。写进去，模型才有可能说"你没有提供面积，我按每平米给"。</p>
     */
    public String describeForPrompt() {
        StringBuilder known = new StringBuilder();
        StringBuilder missing = new StringBuilder();
        for (SituationField field : SituationFields.all()) {
            Object value = values.get(field.getKey());
            if (value == null) {
                if (missing.length() > 0) {
                    missing.append('、');
                }
                missing.append(field.getLabel());
                continue;
            }
            known.append("- ").append(field.getLabel()).append("：").append(value);
            if (field.getUnit() != null) {
                known.append(' ').append(field.getUnit());
            }
            known.append('\n');
        }
        if (known.length() == 0) {
            known.append("- （用户没有提供任何结构化农情数据）\n");
        }
        StringBuilder builder = new StringBuilder(known);
        builder.append("\n【用户未提供的项（不得替用户假定，需要时在方案里说明按什么条件给）】\n");
        builder.append(missing.length() == 0 ? "（无，字段齐全）" : missing.toString());
        return builder.toString();
    }

    /** 按字段类型做归一；空串与空值一律视为"没有值"。 */
    private Object normalize(String key, Object value) {
        SituationField field = SituationFields.byKey(key);
        if (field == null || value == null) {
            return null;
        }
        if (value instanceof Number) {
            double number = ((Number) value).doubleValue();
            return Double.isFinite(number) ? Double.valueOf(number) : null;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        if (field.getKind() == SituationField.Kind.NUMBER) {
            return parseNumber(text);
        }
        if (field.getKind() == SituationField.Kind.DATE) {
            return parseDate(text);
        }
        return text;
    }

    /**
     * 从文本里剥出数字。
     *
     * <p><b>只剥离一个尾随单位，不做模糊匹配</b>：{@code "25℃"} → 25，{@code "80%"} → 80，
     * 但 {@code "25 左右"}、{@code "二三十度"} 一律判为"没有值"。推断这类表达需要语境，
     * 猜错会把一个错误的数字送进推演，而它看起来和真值一样可信。</p>
     */
    private Double parseNumber(String text) {
        StringBuilder digits = new StringBuilder();
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (Character.isDigit(current) || current == '.'
                    || (current == '-' && digits.length() == 0)) {
                digits.append(current);
            } else if (digits.length() > 0) {
                break;
            } else {
                // 数字之前出现非数字字符 → 不是"数字+单位"的形状，判缺失
                return null;
            }
        }
        if (digits.length() == 0 || ".".equals(digits.toString()) || "-".equals(digits.toString())) {
            return null;
        }
        try {
            double parsed = Double.parseDouble(digits.toString());
            return Double.isFinite(parsed) ? Double.valueOf(parsed) : null;
        } catch (NumberFormatException error) {
            return null;
        }
    }

    /**
     * 只接受已经确定的绝对日期（{@code yyyy-MM-dd}，也容忍 {@code yyyy/MM/dd}）。
     *
     * <p><b>相对时间一律返回 null</b>：{@code "上个月"}、{@code "去年秋天"} 的正确解析需要一个
     * 锚点（今天是几号？指的是播种还是取样？），任何推算都是猜。这类原话应当由调用方
     * 塞进字段的 {@code rawText} 供用户自己确认，而不是在这里被换算成一个具体日期。</p>
     */
    private String parseDate(String text) {
        String candidate = text.replace('/', '-').trim();
        if (candidate.length() != 10) {
            return null;
        }
        for (int index = 0; index < candidate.length(); index++) {
            char current = candidate.charAt(index);
            boolean separator = index == 4 || index == 7;
            if (separator ? current != '-' : !Character.isDigit(current)) {
                return null;
            }
        }
        // 只做形状校验，不判"是不是真实存在的日期"——那属于展示层，且闰年之类的判断
        // 在这里做只会多一处可能与用户认知不符的地方。
        return candidate;
    }
}
