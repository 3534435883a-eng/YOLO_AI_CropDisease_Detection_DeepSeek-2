package com.example.Ece.agent.plan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 农情字段的定义（不是取值）。
 *
 * <p><b>为什么定义单独抽出来</b>：同一个字段要在五处被用到——CSV 列名映射、自然语言抽取的提示词、
 * 抽取结果的数值校验、回显确认表单、以及推演提示词里的字段清单。这五处若各写一份字段表，
 * 结果是"加了一个字段但只在其中三处生效"这类静默不一致。因此全部从这一份定义派生。</p>
 *
 * <p><b>{@code aliases} 是模糊映射的唯一依据</b>：CSV 表头命中别名才映射，命中不了就进
 * 映射报告的"未识别列"，<b>绝不按列位置猜</b>——与 {@code AgriEnvironmentController}
 * "取不到就留空、不按天气现象反推 PPFD"是同一条纪律。</p>
 */
public class SituationField {

    /** 字段类型。数值型会被校验器检查区间。 */
    public enum Kind {
        TEXT,
        NUMBER,
        DATE
    }

    private final String key;
    private final String label;
    private final String group;
    private final String unit;
    private final Kind kind;
    private final Double min;
    private final Double max;
    private final List<String> aliases;

    private SituationField(String key, String label, String group, String unit, Kind kind,
                           Double min, Double max, List<String> aliases) {
        this.key = key;
        this.label = label;
        this.group = group;
        this.unit = unit;
        this.kind = kind;
        this.min = min;
        this.max = max;
        this.aliases = Collections.unmodifiableList(new ArrayList<String>(aliases));
    }

    static SituationField text(String key, String group, String label, String... aliases) {
        return new SituationField(key, label, group, null, Kind.TEXT, null, null, Arrays.asList(aliases));
    }

    static SituationField date(String key, String group, String label, String... aliases) {
        return new SituationField(key, label, group, null, Kind.DATE, null, null, Arrays.asList(aliases));
    }

    static SituationField number(String key, String group, String label, String unit, double min, double max,
                                 String... aliases) {
        return new SituationField(key, label, group, unit, Kind.NUMBER,
                Double.valueOf(min), Double.valueOf(max), Arrays.asList(aliases));
    }

    public String getKey() { return key; }

    public String getLabel() { return label; }

    /**
     * 所属分组（中文）。
     *
     * <p>22 个字段平铺在界面上会让人以为只有看得见的那几个——实测截图里正是如此：
     * 滚动区里藏着 17 个字段而没有任何提示。分组是为了让"还能填什么"一眼可见。
     * 分组定义放在这里而不是前端，理由与字段本身一样：两处定义必然漂移。</p>
     */
    public String getGroup() { return group; }

    /** 单位；纯文本字段为 null。 */
    public String getUnit() { return unit; }

    public Kind getKind() { return kind; }

    public Double getMin() { return min; }

    public Double getMax() { return max; }

    public List<String> getAliases() { return aliases; }

    /** 该表头是否属于本字段。比对前做去空格、去全角半角差异的粗归一。 */
    public boolean matchesHeader(String header) {
        String normalized = normalize(header);
        if (normalized.isEmpty()) {
            return false;
        }
        for (String alias : aliases) {
            if (normalize(alias).equals(normalized)) {
                return true;
            }
        }
        // 精确命中优先，其次才允许"表头包含别名"（如"棚内空气温度(℃)"）。
        // 反过来（别名包含表头）不做：那会让"温度"匹配上"土壤温度"，把两个字段混成一个。
        for (String alias : aliases) {
            String normalizedAlias = normalize(alias);
            if (normalizedAlias.length() >= 2 && normalized.contains(normalizedAlias)) {
                return true;
            }
        }
        return false;
    }

    /** 数值是否落在物理合理区间内。非数值字段恒为 true。 */
    public boolean isInRange(double value) {
        if (kind != Kind.NUMBER || min == null || max == null) {
            return true;
        }
        return value >= min.doubleValue() && value <= max.doubleValue();
    }

    /** 区间描述，用于报错文案（"应在 0–45 ℃ 之间"）。 */
    public String rangeText() {
        if (kind != Kind.NUMBER || min == null || max == null) {
            return "";
        }
        return trim(min) + "–" + trim(max) + (unit == null ? "" : unit);
    }

    private static String trim(Double value) {
        double raw = value.doubleValue();
        return raw == Math.floor(raw) ? String.valueOf((long) raw) : String.valueOf(raw);
    }

    /** 去空白、去单位符号、全角转半角，用于表头比对的粗归一。 */
    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (Character.isWhitespace(current)) {
                continue;
            }
            // 全角 ASCII（！-～）转半角；中文标点不在这个区间，保持原样。
            if (current >= '！' && current <= '～') {
                current = (char) (current - 0xFEE0);
            }
            if (current == '(' || current == ')' || current == '（' || current == '）'
                    || current == ':' || current == '：' || current == '·' || current == '/') {
                continue;
            }
            builder.append(current);
        }
        return builder.toString().toLowerCase();
    }
}
