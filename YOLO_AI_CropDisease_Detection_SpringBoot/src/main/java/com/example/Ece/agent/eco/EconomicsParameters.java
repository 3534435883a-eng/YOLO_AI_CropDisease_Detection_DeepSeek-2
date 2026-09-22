package com.example.Ece.agent.eco;

/**
 * 管理与经济参数（模型五）。
 *
 * <p><b>参数来源纪律</b>：本类中的价格均为**示例参数**，用于演示"AI 决策 → 成本收益"的换算链路；
 * 正式参赛材料必须替换为当地实际价格并标注来源与日期（农资店报价、批发市场均价、当地电价文件等），
 * 否则只能标注为示例，不得声称经济效益来自实测经营数据。</p>
 */
public final class EconomicsParameters {

    private EconomicsParameters() {
    }

    /** 灌溉水价（元/m³）。示例参数，需按当地水价核对。 */
    public static final double WATER_YUAN_PER_M3 = 3.5;

    /** 设施农业电价（元/kWh）。示例参数，需按当地农业电价核对。 */
    public static final double ENERGY_YUAN_PER_KWH = 0.65;

    /** CO₂ 气源成本（元/kg）。示例参数。 */
    public static final double CO2_YUAN_PER_KG = 1.2;

    /** 水溶肥成本（元/kg）。示例参数。 */
    public static final double FERTILIZER_YUAN_PER_KG = 4.5;

    /** 植保药剂成本（元/kg）。示例参数。 */
    public static final double PESTICIDE_YUAN_PER_KG = 60.0;

    /** 人工成本（元/工时）。示例参数。 */
    public static final double LABOR_YUAN_PER_HOUR = 25.0;

    /** 固定摊销成本（元/天：设施折旧、管理、水电基础费）。示例参数。 */
    public static final double FIXED_COST_YUAN_PER_DAY = 20.0;

    /** 一等品收购价（元/kg）。示例参数。 */
    public static final double GRADE_A_PRICE_YUAN_PER_KG = 6.0;

    /** 二等品收购价（元/kg）。示例参数。 */
    public static final double GRADE_B_PRICE_YUAN_PER_KG = 3.0;

    /** 一等品比例。示例参数，需按实际分级结果核对。 */
    public static final double GRADE_A_RATIO = 0.7;

    /** 番茄果实干物质率（干重/鲜重）。典型区间 5%–6%，用于把模型输出的果实干重折算为鲜重产量。 */
    public static final double FRUIT_DRY_MATTER_FRACTION = 0.055;

    /** 模拟温室面积（m²），用于把 g/m² 的干重折算为总产量。 */
    public static final double GREENHOUSE_AREA_M2 = 500.0;
}
