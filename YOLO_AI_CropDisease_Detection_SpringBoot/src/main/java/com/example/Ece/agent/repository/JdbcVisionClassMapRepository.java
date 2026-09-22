package com.example.Ece.agent.repository;

import com.example.Ece.agent.vision.VisionLabels;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 视觉检测类别 → 知识库条目 映射（表 {@code agent_vision_class_map}，迁移
 * {@code V20260924_01__agent_vision_class_map.sql} 创建并写入）。
 *
 * <p>这张表回答的问题是："摄像头检出的这个类别，知识库能不能解释？"
 * 只收录经过证据化核验的映射（见 docs/vision-class-kb-mapping.md），未核验的一律 {@code NONE}。</p>
 */
@Repository
public class JdbcVisionClassMapRepository {

    private static final String SELECT_ALL = "SELECT model_code, crop_type, class_index, class_label, label_en, "
            + "label_zh, kb_disease_name, match_rule, evidence, source_url, is_healthy "
            + "FROM agent_vision_class_map ORDER BY crop_type, model_code, class_index";

    private final JdbcTemplate jdbcTemplate;

    public JdbcVisionClassMapRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Map<String, Object>> findAll() {
        return jdbcTemplate.query(SELECT_ALL, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("modelCode", rs.getString("model_code"));
            row.put("cropType", rs.getString("crop_type"));
            row.put("classIndex", Integer.valueOf(rs.getInt("class_index")));
            row.put("classLabel", rs.getString("class_label"));
            row.put("labelEn", rs.getString("label_en"));
            row.put("labelZh", rs.getString("label_zh"));
            row.put("kbDiseaseName", rs.getString("kb_disease_name"));
            row.put("matchRule", rs.getString("match_rule"));
            row.put("evidence", rs.getString("evidence"));
            row.put("sourceUrl", rs.getString("source_url"));
            row.put("healthy", Boolean.valueOf(rs.getInt("is_healthy") == 1));
            row.put("explainable", Boolean.valueOf(rs.getString("kb_disease_name") != null));
            return row;
        });
    }

    /**
     * 按类别标签查映射（标签做归一化：去引号/空白、全角括号转半角、忽略大小写）。
     * 给了作物则优先取该作物的行；返回 null 表示该标签不在 56 个已登记类别内。
     */
    public Map<String, Object> findByClassLabel(String classLabel, String crop) {
        if (classLabel == null || classLabel.trim().isEmpty()) {
            return null;
        }
        String target = VisionLabels.normalizeLabel(classLabel).toLowerCase(Locale.ROOT);
        // 记录里的标签常写成"En(中文)"，而映射表里中文存在 label_zh；额外按中文部分比对一次，
        // 避免英文大小写/括号风格差异导致漏配（实测玉米记录就是这样没配上）。
        String targetZh = zhPartOf(target);
        Map<String, Object> fallback = null;
        for (Map<String, Object> row : findAll()) {
            String label = VisionLabels.normalizeLabel(String.valueOf(row.get("classLabel"))).toLowerCase(Locale.ROOT);
            String zh = VisionLabels.normalizeLabel(String.valueOf(row.get("labelZh"))).toLowerCase(Locale.ROOT);
            String en = VisionLabels.normalizeLabel(String.valueOf(row.get("labelEn"))).toLowerCase(Locale.ROOT);
            boolean hit = target.equals(label) || (!zh.isEmpty() && target.equals(zh)) || (!en.isEmpty() && target.equals(en))
                    || (!targetZh.isEmpty() && !zh.isEmpty() && targetZh.equals(zh));
            if (!hit) {
                continue;
            }
            if (crop != null && crop.equals(row.get("cropType"))) {
                return row;
            }
            if (fallback == null) {
                fallback = row;
            }
        }
        return fallback;
    }

    /** 取"En(中文)"里的中文部分；没有括号则返回空串。 */
    private String zhPartOf(String normalizedLabel) {
        int open = normalizedLabel.indexOf('(');
        int close = normalizedLabel.lastIndexOf(')');
        if (open < 0 || close <= open + 1) {
            return "";
        }
        return normalizedLabel.substring(open + 1, close).trim();
    }

    /** 统计：可检出类别总数、其中知识库能解释的、不能解释的（用于展示覆盖缺口）。 */
    public Map<String, Object> summarize() {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        for (Map<String, Object> row : findAll()) {
            if (Boolean.TRUE.equals(row.get("healthy"))) {
                continue;
            }
            increment(summary, "detectableCrops", String.valueOf(row.get("cropType")));
            if (Boolean.TRUE.equals(row.get("explainable"))) {
                increment(summary, "explainable", String.valueOf(row.get("cropType")));
            } else {
                increment(summary, "unexplained", String.valueOf(row.get("cropType")));
            }
        }
        return summary;
    }

    private void increment(Map<String, Object> summary, String key, String crop) {
        Object existing = summary.get(key);
        Map<String, Integer> counter = existing instanceof Map
                ? castMap(existing) : new LinkedHashMap<String, Integer>();
        Integer current = counter.get(crop);
        counter.put(crop, Integer.valueOf(current == null ? 1 : current.intValue() + 1));
        summary.put(key, counter);
        summary.put(key + "Total", Integer.valueOf(sum(summary, key)));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Integer> castMap(Object value) {
        return (Map<String, Integer>) value;
    }

    private int sum(Map<String, Object> summary, String key) {
        int total = 0;
        for (Integer value : castMap(summary.get(key)).values()) {
            total += value.intValue();
        }
        return total;
    }
}