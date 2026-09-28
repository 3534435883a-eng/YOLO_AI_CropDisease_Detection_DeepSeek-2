package com.example.Ece.agent.plan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 农情字段的**唯一登记表**。CSV 映射、自然语言抽取、校验、回显表单、推演提示词全部由它派生。
 *
 * <p><b>关于数值区间</b>：这些区间是"物理上不可能"的判据，<b>不是农艺适宜区间</b>。
 * 例如温度取 -20–60 ℃——冬季不加温的棚到 -5 ℃ 是真实存在的，"超出最适区间"不该报错。
 * 区间只用来拦"把 250 打成 2500"这类录入错误。把适宜区间写进这里，会让系统对真实但不利的
 * 读数报错，那是在拒绝用户告诉我们的真话。</p>
 */
public final class SituationFields {

    /** 数据来源标记。回显确认时前端据此区分"用户填的"与"从原话里抽的"。 */
    public static final String SOURCE_USER = "USER";
    public static final String SOURCE_PARSED = "PARSED";

    /** 分组名。字段按组连续排列，前端据此折叠展示。 */
    public static final String GROUP_BASIC = "基本情况";
    public static final String GROUP_ENVIRONMENT = "环境读数";
    public static final String GROUP_MANAGEMENT = "管理情况";
    public static final String GROUP_PEST = "病虫害";
    public static final String GROUP_GOAL = "生产目标";

    private static final List<SituationField> ALL;

    static {
        List<SituationField> fields = new ArrayList<SituationField>();

        fields.add(SituationField.text("crop", GROUP_BASIC, "作物", "作物", "作物名称", "品种名称"));
        fields.add(SituationField.text("variety", GROUP_BASIC, "品种", "品种", "品种名"));
        fields.add(SituationField.text("facilityType", GROUP_BASIC, "设施类型",
                "设施类型", "棚型", "温室类型", "大棚类型"));
        fields.add(SituationField.number("areaM2", GROUP_BASIC, "面积", "m²", 0, 100_000,
                "面积", "种植面积", "棚面积"));
        fields.add(SituationField.date("plantingDate", GROUP_BASIC, "定植日期",
                "定植日期", "定植时间", "播种日期", "移栽日期"));
        fields.add(SituationField.text("growthStage", GROUP_BASIC, "当前生育期",
                "生育期", "当前生育期", "生长阶段", "物候期"));
        fields.add(SituationField.text("cultivation", GROUP_BASIC, "栽培方式",
                "栽培方式", "栽培模式", "种植方式", "基质类型"));

        fields.add(SituationField.number("temperatureC", GROUP_ENVIRONMENT, "温度", "℃", -20, 60,
                "温度", "气温", "棚温", "空气温度", "室温"));
        fields.add(SituationField.number("humidityPct", GROUP_ENVIRONMENT, "空气湿度", "%", 0, 100,
                "湿度", "空气湿度", "相对湿度", "棚内湿度"));
        fields.add(SituationField.number("soilMoisturePct", GROUP_ENVIRONMENT, "土壤含水率", "%", 0, 100,
                "土壤含水率", "土壤湿度", "基质含水率", "土壤水分"));
        fields.add(SituationField.number("co2Ppm", GROUP_ENVIRONMENT, "CO₂ 浓度", "ppm", 200, 5_000,
                "co2", "二氧化碳", "co2浓度", "二氧化碳浓度"));
        fields.add(SituationField.number("lightPpfd", GROUP_ENVIRONMENT, "光照",
                "μmol·m⁻²·s⁻¹", 0, 2_500, "光照", "光照强度", "ppfd", "光强"));
        fields.add(SituationField.number("vpdKpa", GROUP_ENVIRONMENT, "水汽压亏缺", "kPa", 0, 10,
                "vpd", "水汽压亏缺", "饱和差"));

        fields.add(SituationField.text("irrigation", GROUP_MANAGEMENT, "灌溉方式与频次",
                "灌溉", "灌溉方式", "灌溉频次", "浇水"));
        fields.add(SituationField.text("fertilization", GROUP_MANAGEMENT, "施肥方案",
                "施肥", "施肥方案", "肥料", "追肥"));
        fields.add(SituationField.text("recentOperations", GROUP_MANAGEMENT, "近期农事操作",
                "近期操作", "近期农事", "农事操作"));

        fields.add(SituationField.text("symptoms", GROUP_PEST, "田间症状描述",
                "症状", "症状描述", "病害症状", "虫害症状"));
        fields.add(SituationField.text("detectedDisease", GROUP_PEST, "已确诊病虫害",
                "已确诊", "确诊病害", "病虫害名称", "病害名称"));
        fields.add(SituationField.text("severity", GROUP_PEST, "发生程度",
                "发生程度", "严重度", "发病程度"));

        fields.add(SituationField.text("goal", GROUP_GOAL, "生产目标",
                "目标", "生产目标", "首要目标"));
        fields.add(SituationField.date("targetMarketDate", GROUP_GOAL, "目标上市期",
                "目标上市期", "上市时间", "计划上市"));
        fields.add(SituationField.number("budgetYuan", GROUP_GOAL, "预算", "元", 0, 100_000_000,
                "预算", "投入预算", "成本预算"));

        ALL = Collections.unmodifiableList(fields);
    }

    /** 分组名，按字段登记顺序去重。前端据此确保分组顺序与登记表一致。 */
    public static List<String> groups() {
        List<String> groups = new ArrayList<String>();
        for (SituationField field : ALL) {
            if (!groups.contains(field.getGroup())) {
                groups.add(field.getGroup());
            }
        }
        return groups;
    }

    private SituationFields() {
    }

    public static List<SituationField> all() {
        return ALL;
    }

    public static SituationField byKey(String key) {
        for (SituationField field : ALL) {
            if (field.getKey().equals(key)) {
                return field;
            }
        }
        return null;
    }

    /**
     * 按表头找字段。命中不了返回 {@code null}——调用方必须把"未识别"报出来，
     * 而不是回落到某个默认字段。
     */
    public static SituationField byHeader(String header) {
        for (SituationField field : ALL) {
            if (field.matchesHeader(header)) {
                return field;
            }
        }
        return null;
    }

    /** key → 中文标签，供前端与日志使用。 */
    public static Map<String, String> labels() {
        Map<String, String> labels = new LinkedHashMap<String, String>();
        for (SituationField field : ALL) {
            labels.put(field.getKey(), field.getLabel());
        }
        return labels;
    }

    /**
     * 生成给模型看的字段清单（用于自然语言抽取的提示词）。
     *
     * <p>带上单位与"未知填 null"的要求：不让模型看到单位，它会返回"25摄氏度"这样的字符串，
     * 后面的数值校验只能一律判缺失。</p>
     */
    public static String describeForExtraction() {
        StringBuilder builder = new StringBuilder();
        for (SituationField field : ALL) {
            builder.append("- ").append(field.getKey()).append("（").append(field.getLabel()).append("）");
            switch (field.getKind()) {
                case NUMBER:
                    builder.append(" 数值，单位 ").append(field.getUnit())
                            .append("，合理区间 ").append(field.rangeText());
                    break;
                case DATE:
                    builder.append(" 日期，(yyyy-MM-dd)");
                    break;
                default:
                    builder.append(" 文本");
                    break;
            }
            builder.append('\n');
        }
        return builder.toString();
    }
}
