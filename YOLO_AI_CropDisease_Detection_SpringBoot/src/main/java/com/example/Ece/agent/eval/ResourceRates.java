package com.example.Ece.agent.eval;

import com.example.Ece.agent.engine.TomatoSimulationEngine;

/** 未校准资源定额；全部 PER_STEP 数值以 15 分钟参考步长给出，调用方按实际分钟折算。
 * 全棚设备规模按 M3 的 1600 m² 折算；灌溉浇在 252 m² 试验区。
 * 面积比例不代表真实铭牌功率，不能作为 M3 实测耗量。 */
public final class ResourceRates {
    private ResourceRates() { }
    public static final int REFERENCE_MINUTES = 15;
    private static final double FOOTPRINT_SCALE = TomatoSimulationEngine.GREENHOUSE_FOOTPRINT_M2 / 500.0;
    public static final double VENTILATION_KWH_PER_STEP = 0.08 * FOOTPRINT_SCALE;
    public static final double GROW_LIGHT_KWH_PER_STEP = 0.35 * FOOTPRINT_SCALE;
    public static final double SHADE_KWH_PER_STEP = 0.01 * FOOTPRINT_SCALE;
    public static final double ROOF_VENT_KWH_PER_STEP = 0.01 * FOOTPRINT_SCALE;
    public static final double EXHAUST_FAN_KWH_PER_STEP = 0.22 * FOOTPRINT_SCALE;
    public static final double COOLING_PAD_KWH_PER_STEP = 0.06 * FOOTPRINT_SCALE;
    public static final double CIRCULATION_FAN_KWH_PER_STEP = 0.025 * FOOTPRINT_SCALE;
    public static final double IRRIGATION_PUMP_KWH_PER_STEP = 0.0375 * FOOTPRINT_SCALE;
    /** 假设加热电耗，非 M3 设备容量。 */
    public static final double HEATING_KWH_PER_STEP = 5.0 * FOOTPRINT_SCALE;
    public static final double CO2_KG_PER_STEP = 0.250;
    public static final double IRRIGATION_M3_PER_STEP = TomatoSimulationEngine.IRRIGATION_L_PER_TICK / 1000.0;
    public static final double LABOR_HOURS_PER_STEP = 0.005;
    public static final double FERTILIZER_KG_PER_HA_PER_STEP = 0.02;
    public static final double MANUAL_FERTILIZER_KG_PER_HA_PER_WEEK = 12.0;
}
