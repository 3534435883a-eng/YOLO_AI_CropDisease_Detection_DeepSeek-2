package com.example.Ece.agent.eval;

import com.example.Ece.agent.engine.TomatoSimulationEngine;

/**
 * 设备资源消耗速率（示例参数）。
 *
 * <p>用于把"设备开没开"折算为水、电、CO₂ 的实物消耗量；数值为设施番茄温室的**示例参数**，
 * 正式材料需替换为当地设备铭牌功率与实际水表/电表记录，并标注来源。</p>
 */
public final class ResourceRates {

    private ResourceRates() {
    }

    /**
     * 规模基准修正系数。
     *
     * <p><b>为什么要乘这个系数</b>：下列耗能/CO₂ 定额原本的注释写的是"按 500 m² 温室估算"，
     * 而本模型的棚体是 **26 m × 13 m = 338 m²**（见
     * {@code TomatoSimulationEngine.GREENHOUSE_FOOTPRINT_M2} 与
     * docs/tomato-greenhouse-agent.md）。风机、补光灯、湿帘泵的耗能与棚体规模大致成正比，
     * 按 500 m² 估就是**凭空多算 500/338 = 1.48 倍**。</p>
     *
     * <p>2026-09-26 实测：产量侧已按种植床 142.8 m² 修正，而耗能侧仍按 500 m²，
     * 两侧规模基准不一致，全部档位利润由正转负。此处把基准统一到**本棚棚体面积**。</p>
     *
     * <p><b>注意这仍然只是"把猜测值换到正确的规模基准上"</b>：定额本身依旧是示例参数，
     * 要成为可用值需要**设备铭牌功率 + 实际水表/电表记录**，不是靠折算能得到的。</p>
     */
    private static final double FOOTPRINT_SCALE =
            TomatoSimulationEngine.GREENHOUSE_FOOTPRINT_M2 / 500.0;

    /** 单步（15 分钟）通风风机耗电（kWh，按**本棚棚体 338 m²** 折算，见 FOOTPRINT_SCALE）。示例参数。 */
    public static final double VENTILATION_KWH_PER_STEP = 0.08 * FOOTPRINT_SCALE;

    /** 单步补光耗电（kWh）。示例参数。 */
    public static final double GROW_LIGHT_KWH_PER_STEP = 0.35 * FOOTPRINT_SCALE;

    /** 单步遮阳电机耗电（kWh）。示例参数。 */
    public static final double SHADE_KWH_PER_STEP = 0.01 * FOOTPRINT_SCALE;

    /**
     * 单步屋窗开闭电机耗电（kWh）。示例参数。
     *
     * <p>屋窗、排风与湿帘此前未折算任何能耗，导致"用排风代替通风"在成本上被记为免费，
     * 连续控制与规则控制之间的成本对比会失真。此处补齐为同一口径的示例参数。</p>
     */
    public static final double ROOF_VENT_KWH_PER_STEP = 0.01 * FOOTPRINT_SCALE;

    /** 单步强制排风耗电（kWh）。示例参数：排风机通常大于常规通风风机。 */
    public static final double EXHAUST_FAN_KWH_PER_STEP = 0.22 * FOOTPRINT_SCALE;

    /** 单步湿帘循环水泵耗电（kWh）。示例参数。 */
    public static final double COOLING_PAD_KWH_PER_STEP = 0.06 * FOOTPRINT_SCALE;

    /** 单步 CO₂ 补给消耗（kg）。示例参数。 */
    public static final double CO2_KG_PER_STEP = 0.05 * FOOTPRINT_SCALE;

    /**
     * 单步滴灌用水（m³）。示例参数，取值 0.06 m³ = 60 L/步。
     *
     * <p><b>2026-09-26 由 0.6 改为 0.06。</b>原值 0.6 m³（600 L）与另外四处相矛盾：
     * 物理侧 {@code TomatoSimulationEngine.IRRIGATION_L_PER_TICK = 60.0}、
     * 规则层 {@code TomatoDecisionPolicy} 声明的 {@code "WATER", "60.000"}`、
     * 数字孪生运行时按 60 L/次计、以及 {@code docs/tomato-greenhouse-agent.md} 正文写的"滴灌 60 L/次"。
     * 也就是说**浇下去的是 60 L，记在账上的是 600 L**，该差额直接进入成本与利润。
     * 四处对一处，判定记账侧是错误，予以统一。</p>
     *
     * <p>影响：本次修改会改变各档"水耗 / 成本 / 利润"的绝对值（此前这些数值偏高约 10 倍的水费部分），
     * 但**不改变任何档之间的相对关系**（同口径缩放）。{@code ResourceAccountingTest} 现在会锁住
     * 物理侧与记账侧的一致性，防止再次漂移。</p>
     */
    public static final double IRRIGATION_M3_PER_STEP = 0.06;

    /** 单步人工投入（工时）。示例参数：约 0.5 工时/天，对应半自动温室的日常巡检与处置。 */
    public static final double LABOR_HOURS_PER_STEP = 0.005;

    /** 规则/智能体策略下单步施肥量（kg/ha）。示例参数。 */
    public static final double FERTILIZER_KG_PER_HA_PER_STEP = 0.02;

    /** 人工策略的施肥频次：每 7 天一次，单次施肥量（kg/ha）。示例参数。 */
    public static final double MANUAL_FERTILIZER_KG_PER_HA_PER_WEEK = 12.0;
}
