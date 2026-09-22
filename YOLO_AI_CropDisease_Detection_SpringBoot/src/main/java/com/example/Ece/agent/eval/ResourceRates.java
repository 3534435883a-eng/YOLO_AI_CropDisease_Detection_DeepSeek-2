package com.example.Ece.agent.eval;

/**
 * 设备资源消耗速率（示例参数）。
 *
 * <p>用于把"设备开没开"折算为水、电、CO₂ 的实物消耗量；数值为设施番茄温室的**示例参数**，
 * 正式材料需替换为当地设备铭牌功率与实际水表/电表记录，并标注来源。</p>
 */
public final class ResourceRates {

    private ResourceRates() {
    }

    /** 单步（15 分钟）通风风机耗电（kWh，按 500 m² 温室估算）。示例参数。 */
    public static final double VENTILATION_KWH_PER_STEP = 0.08;

    /** 单步补光耗电（kWh）。示例参数。 */
    public static final double GROW_LIGHT_KWH_PER_STEP = 0.35;

    /** 单步遮阳电机耗电（kWh）。示例参数。 */
    public static final double SHADE_KWH_PER_STEP = 0.01;

    /** 单步 CO₂ 补给消耗（kg）。示例参数。 */
    public static final double CO2_KG_PER_STEP = 0.05;

    /** 单步滴灌用水（m³，按 500 m² 温室与 1.2 mm/步估算）。示例参数。 */
    public static final double IRRIGATION_M3_PER_STEP = 0.6;

    /** 单步人工投入（工时）。示例参数：约 0.5 工时/天，对应半自动温室的日常巡检与处置。 */
    public static final double LABOR_HOURS_PER_STEP = 0.005;

    /** 规则/智能体策略下单步施肥量（kg/ha）。示例参数。 */
    public static final double FERTILIZER_KG_PER_HA_PER_STEP = 0.02;

    /** 人工策略的施肥频次：每 7 天一次，单次施肥量（kg/ha）。示例参数。 */
    public static final double MANUAL_FERTILIZER_KG_PER_HA_PER_WEEK = 12.0;
}
