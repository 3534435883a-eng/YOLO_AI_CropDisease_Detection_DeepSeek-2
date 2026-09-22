package com.example.Ece.agent.vision;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 识别记录的字段解析：把遗留 {@code imgrecords} 表里的实际存储形态转成可用结构。
 *
 * <p><b>为什么单独抽出来</b>：该表的字段形态并不统一（以下均为 634 条真实记录的实测结果）：</p>
 * <ul>
 *   <li>{@code label} 多数是 JSON 数组字符串（{@code ["Rust(玉米锈病)"]}），
 *       但苹果的记录里有 {@code "Alternaria leaf spot"} 这种**单个带引号的英文字符串**；</li>
 *   <li>同一行可能有**多个标签**（{@code ["Blight(枯萎病)","Rust(玉米锈病)"]}），也有重复标签
 *       （{@code ["FAW_Lv(秋军虫幼虫病)","FAW_Lv(秋军虫幼虫病)"]}）；</li>
 *   <li>标签里的括号有**全角**形式（{@code blight（疫病）}）与半角形式混用；</li>
 *   <li>{@code confidence} 是**百分数字符串数组**（{@code ["99.11%"]}），
 *       旧实现按单值剥离非数字字符会把 99.11 变成 9911 再被夹到 1.00（实测 bug）。</li>
 *   <li>作物由 {@code kind}（作物代码：tomato/rice/corn/corn1…）给出，{@code weight}（模型文件名）可兜底。</li>
 * </ul>
 */
public final class VisionLabels {

    private VisionLabels() {
    }

    /** 一条识别结果：类别标签 + 置信度（0~1，可能为 null）。 */
    public static final class Detection {
        private final String label;
        private final BigDecimal confidence;

        public Detection(String label, BigDecimal confidence) {
            this.label = label;
            this.confidence = confidence;
        }

        public String getLabel() {
            return label;
        }

        public BigDecimal getConfidence() {
            return confidence;
        }
    }

    private static final Map<String, String> CROP_BY_CODE;

    static {
        Map<String, String> crops = new LinkedHashMap<String, String>();
        crops.put("tomato", "番茄");
        crops.put("corn", "玉米");
        // 历史数据里存在第二个玉米模型（corn1），同样归到玉米
        crops.put("corn1", "玉米");
        crops.put("rice", "水稻");
        crops.put("wheat", "小麦");
        crops.put("potato", "马铃薯");
        crops.put("cotton", "棉花");
        crops.put("apple", "苹果");
        crops.put("grape", "葡萄");
        crops.put("strawberry", "草莓");
        CROP_BY_CODE = Collections.unmodifiableMap(crops);
    }

    /** 由 {@code kind}（作物代码）判定规范作物名；判定不出回退到 {@code weight}（模型文件名）；都判定不出返回 null。 */
    public static String cropOf(String kind, String weight) {
        String code = normalizeCode(kind);
        if (code == null) {
            code = normalizeCode(weight);
        }
        return code == null ? null : CROP_BY_CODE.get(code);
    }

    /** 归一化作物代码：小写、去空白、去模型文件后缀与非字母数字字符。 */
    static String normalizeCode(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().toLowerCase();
        int dot = value.indexOf('.');
        if (dot > 0) {
            value = value.substring(0, dot);
        }
        if (value.endsWith("_best")) {
            value = value.substring(0, value.length() - "_best".length());
        }
        value = value.replaceAll("[^a-z0-9]", "");
        return value.isEmpty() ? null : value;
    }

    /**
     * 解析 label 与 confidence。支持 JSON 数组、单个带引号字符串与纯文本；
     * 标签按出现顺序去重（同一行重复标签只保留一条），置信度按下标对齐。
     */
    public static List<Detection> parse(String labelJson, String confidenceJson) {
        List<String> labels = toStringList(labelJson);
        List<BigDecimal> confidences = toConfidenceList(confidenceJson);
        List<Detection> detections = new ArrayList<Detection>();
        Set<String> seen = new LinkedHashSet<String>();
        for (int i = 0; i < labels.size(); i++) {
            String label = normalizeLabel(labels.get(i));
            if (label.isEmpty() || !seen.add(label)) {
                continue;
            }
            detections.add(new Detection(label, i < confidences.size() ? confidences.get(i) : null));
        }
        return detections;
    }

    /** 主检测：置信度最高者；置信度全为空时取第一条。表结构一条来源记录只存一行，因此需要选主。 */
    public static Detection primary(List<Detection> detections) {
        if (detections == null || detections.isEmpty()) {
            return null;
        }
        Detection best = null;
        for (Detection detection : detections) {
            if (best == null) {
                best = detection;
                continue;
            }
            if (detection.getConfidence() == null) {
                continue;
            }
            if (best.getConfidence() == null || detection.getConfidence().compareTo(best.getConfidence()) > 0) {
                best = detection;
            }
        }
        return best;
    }

    /** 归一化标签：去首尾引号与空白、全角括号/冒号转半角、压缩连续空白。 */
    public static String normalizeLabel(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        // 去零宽字符：模型类别名里实测含 U+200B（如 "\u200bRust(玉米锈病)"），不清理会导致映射查不到。
        value = value.replaceAll("[\\u200b\\u200c\\u200d\\ufeff]", "");
        value = value.replace('（', '(').replace('）', ')').replace('：', ':');
        return value.replaceAll("\\s+", " ").trim();
    }

    private static List<String> toStringList(String raw) {
        List<String> result = new ArrayList<String>();
        if (raw == null || raw.trim().isEmpty()) {
            return result;
        }
        String text = raw.trim();
        if (text.startsWith("[") || text.startsWith("\"") || text.startsWith("{")) {
            try {
                Object parsed = JSON.parse(text);
                if (parsed instanceof JSONArray) {
                    JSONArray array = (JSONArray) parsed;
                    for (int i = 0; i < array.size(); i++) {
                        Object item = array.get(i);
                        if (item != null) {
                            result.add(String.valueOf(item));
                        }
                    }
                    return result;
                }
                if (parsed instanceof String) {
                    result.add((String) parsed);
                    return result;
                }
            } catch (RuntimeException ignored) {
                // 解析失败则按纯文本处理（例如"预测失败"）
            }
        }
        result.add(text);
        return result;
    }

    private static List<BigDecimal> toConfidenceList(String raw) {
        List<BigDecimal> result = new ArrayList<BigDecimal>();
        if (raw == null || raw.trim().isEmpty()) {
            return result;
        }
        String text = raw.trim();
        List<String> items = new ArrayList<String>();
        if (text.startsWith("[")) {
            try {
                JSONArray array = JSON.parseArray(text);
                for (int i = 0; i < array.size(); i++) {
                    Object item = array.get(i);
                    if (item != null) {
                        items.add(String.valueOf(item));
                    }
                }
            } catch (RuntimeException ignored) {
                items.add(text);
            }
        } else {
            items.add(text);
        }
        for (String item : items) {
            result.add(toConfidence(item));
        }
        return result;
    }

    /** 百分数或小数统一转成 0~1；无法解析返回 null（不猜测）。 */
    static BigDecimal toConfidence(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = raw.replace("%", "").replaceAll("[^0-9.\\-]", "").trim();
        if (cleaned.isEmpty()) {
            return null;
        }
        try {
            BigDecimal value = new BigDecimal(cleaned);
            if (value.compareTo(BigDecimal.ONE) > 0) {
                value = value.divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP);
            }
            return value.max(BigDecimal.ZERO).min(BigDecimal.ONE);
        } catch (NumberFormatException error) {
            return null;
        }
    }
}