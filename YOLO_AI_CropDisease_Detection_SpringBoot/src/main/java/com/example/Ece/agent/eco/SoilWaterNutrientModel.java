package com.example.Ece.agent.eco;

import com.example.Ece.agent.crop.CropStage;
import com.example.Ece.agent.crop.TomatoCropState;
import com.example.Ece.agent.model.SimulationState;
import org.springframework.stereotype.Component;

/**
 * 番茄温室土壤水分—养分耦合模型（简化水量平衡 bucket 模型，模型 1）。
 *
 * <p>模型把温室环境（{@link SimulationState}）与作物状态（{@link TomatoCropState}）转换为土壤状态增量：</p>
 * <ol>
 *   <li>FAO-56 思路：简化 Hargreaves 参考蒸散 ET0 × 作物系数 Kc → 作物蒸散 ETc；</li>
 *   <li>水量平衡：灌溉 - ETc 换算为根区含水率变化，超过田间持水量的部分向下排水；</li>
 *   <li>养分平衡：施肥投入 - 作物带走 - 淋洗损失，水池不低于 0；</li>
 *   <li>盐分平衡：肥料带入盐分、排水与淋洗带走盐分；</li>
 *   <li>土壤 pH：肥料微酸化、灌溉微抬升，限幅在适宜区间。</li>
 * </ol>
 *
 * <p><b>纯函数约定：</b>{@link #advance} 不保存任何可变实例状态，输出只是
 * {@code (current, environment, crop, minutes, irrigationOn, fertilizerKgPerHa)} 的确定函数；
 * 本段 {@code minutes} 的增量统一按 {@code minutes / 1440.0} 折算成“日尺度”速率。
 * 模型不使用随机数、时钟或任何非确定性来源，因此同样输入必然得到同样输出。</p>
 *
 * <p>所有输出均为模拟量，不代表任何实测数据。</p>
 */
@Component
public class SoilWaterNutrientModel {

    /** 一天的分钟数，用于把分钟片段折算为日尺度速率。 */
    private static final double MINUTES_PER_DAY = 1440.0;

    /** 根区 1 mm 水量对应的体积含水率变化（%vol/mm）。 */
    private static final double MM_TO_PCT_VOL = 1.6;

    /** 简化 Hargreaves 式系数。 */
    private static final double HARGREAVES_COEFFICIENT = 0.0023;

    /** 简化 Hargreaves 式的温度偏移（摄氏度）。 */
    private static final double HARGREAVES_TEMPERATURE_OFFSET_C = 17.8;

    /** 辐射代理量下限，避免夜间/弱光时开方为 0。 */
    private static final double RADIATION_PROXY_FLOOR = 0.1;

    /** 辐射代理量除数（PPFD -> 辐射代理量）。 */
    private static final double RADIATION_PROXY_DIVISOR = 100.0;

    /** 辐射项换算系数（MJ·m^-2·d^-1 -> mm/d）。 */
    private static final double ET0_RADIATION_COEFFICIENT = 0.408;

    /** 含水率下限系数（相对凋萎点）。 */
    private static final double MOISTURE_FLOOR_RATIO = 0.5;

    /** 含水率上限余量（相对田间持水量，%vol）。 */
    private static final double MOISTURE_HEADROOM_PCT = 5.0;

    /** 排水淋洗氮占“排水量 × 养分下限”的比例。 */
    private static final double LEACHED_NITROGEN_FRACTION = 0.01;

    /** 复合肥中氮的分配比例。 */
    private static final double FERTILIZER_NITROGEN_SHARE = 0.15;

    /** 复合肥中磷的分配比例。 */
    private static final double FERTILIZER_PHOSPHORUS_SHARE = 0.07;

    /** 复合肥中钾的分配比例。 */
    private static final double FERTILIZER_POTASSIUM_SHARE = 0.20;

    /**
     * 干物质 → 养分的换算系数（g·m^-2 折算为 kg/ha）。
     *
     * <p>换算链：1 g/m^2 = 10 kg/ha，本模型再乘一次 10 作为根区养分核算的简化放大，
     * 合计乘 100；这是一处刻意保留的简化，便于与“每 kg 干物质养分带走量”直接相乘。</p>
     */
    private static final double DRY_MATTER_TO_NUTRIENT_KG_PER_HA = 100.0;

    /** 每日周转/新生的干物质占现有总干重的比例（用于近似当步作物养分带走量）。 */
    private static final double DAILY_DRY_MATTER_TURNOVER_RATIO = 0.02;

    /** 每 kg/ha 肥料造成的 pH 下降（酸化）。 */
    private static final double PH_ACIDIFICATION_PER_KG_FERTILIZER = 0.0005;

    /** 每 mm 灌溉水造成的 pH 抬升（灌溉水接近中性，把偏酸土壤向 6.5 附近抬升）。 */
    private static final double PH_RAISE_PER_MM_IRRIGATION = 0.002;

    /** EC 下限（dS/m）。 */
    private static final double EC_FLOOR_DS_PER_M = 0.3;

    /** 环境缺失时的默认温度（摄氏度）。 */
    private static final double DEFAULT_TEMPERATURE_C = 22.0;

    /** 环境缺失时的默认光强（PPFD）。 */
    private static final double DEFAULT_LIGHT_PPFD = 0.0;

    /**
     * 初始土壤状态：适宜含水率、低盐、中性偏酸、养分充足。
     *
     * @return 初始土壤状态
     */
    public SoilState initial() {
        return new SoilState(62.0, 1.2, 6.2, 90.0, 45.0, 140.0, 0.0, 0.0, 1.0);
    }

    /**
     * 推进一步（{@code minutes} 分钟），返回新的不可变土壤状态。
     *
     * <p>本方法为纯函数：不读写任何实例字段，只依赖入参。</p>
     *
     * @param current            当前土壤状态；为 {@code null} 时按 {@link #initial()} 处理
     * @param environment        当前温室环境（模拟量）；为 {@code null} 时按默认环境处理
     * @param crop               当前作物状态；为 {@code null} 时作物系数取 {@code KC_MID} 且不计算养分带走
     * @param minutes            本次推进的分钟数（负数按 0 处理）
     * @param irrigationOn       本次是否开启灌溉
     * @param fertilizerKgPerHa  本次投加的肥料量（kg/ha，负值按 0 处理）
     * @return 推进后的土壤状态
     */
    public SoilState advance(SoilState current, SimulationState environment, TomatoCropState crop,
                             int minutes, boolean irrigationOn, double fertilizerKgPerHa) {
        SoilState base = current == null ? initial() : current;
        int sliceMinutes = Math.max(0, minutes);
        double factor = sliceMinutes / MINUTES_PER_DAY;
        double doseKgPerHa = Math.max(0.0, fertilizerKgPerHa);

        double temperatureC = environment == null ? DEFAULT_TEMPERATURE_C : environment.getTemperatureC();
        double lightPpfd = environment == null ? DEFAULT_LIGHT_PPFD : environment.getLightPpfd();

        // 1) 参考蒸散 ET0：由日均温与日辐射代理量驱动的简化 Hargreaves 式（mm/day）
        double radiationProxy = Math.sqrt(Math.max(RADIATION_PROXY_FLOOR, lightPpfd / RADIATION_PROXY_DIVISOR));
        double et0MmPerDay = HARGREAVES_COEFFICIENT * (temperatureC + HARGREAVES_TEMPERATURE_OFFSET_C)
                * radiationProxy * ET0_RADIATION_COEFFICIENT;

        // 2) 作物蒸散 ETc = Kc × ET0 × 时间片（mm/本步）
        double kc = cropCoefficient(crop);
        double etcMm = kc * et0MmPerDay * factor;

        // 3) 灌溉量（mm/本步）
        double irrigationMm = irrigationOn ? SoilParameters.IRRIGATION_MM_PER_STEP * factor : 0.0;

        // 4) 水量平衡：mm 换算为体积含水率变化，超出田间持水量的部分视为向下排水
        double deltaPct = (irrigationMm - etcMm) * MM_TO_PCT_VOL;
        double rawMoisturePct = base.getSoilMoisturePct() + deltaPct;
        double drainagePct = Math.max(0.0, rawMoisturePct - SoilParameters.FIELD_CAPACITY_PCT);
        double soilMoisturePct = clamp(rawMoisturePct - drainagePct,
                SoilParameters.WILTING_POINT_PCT * MOISTURE_FLOOR_RATIO,
                SoilParameters.FIELD_CAPACITY_PCT + MOISTURE_HEADROOM_PCT);

        // 5) 养分平衡：施肥投入 - 作物带走 - 排水淋洗
        //    简化说明：本模型不保存“上一步干重”，无法得到真实增量 ΔW；
        //    这里按“总干重 × 0.02 / 天 × 时间片”近似当步新生（周转）干物质量，
        //    再乘单位干物质养分带走量得到养分带走量。该近似是刻意保留的模型简化。
        double dryMatterTurnover = crop == null
                ? 0.0
                : Math.max(0.0, crop.getWTotal()) * DAILY_DRY_MATTER_TURNOVER_RATIO * factor;
        double nitrogenUptake = SoilParameters.N_PER_KG_DM * dryMatterTurnover * DRY_MATTER_TO_NUTRIENT_KG_PER_HA;
        double phosphorusUptake = SoilParameters.P_PER_KG_DM * dryMatterTurnover * DRY_MATTER_TO_NUTRIENT_KG_PER_HA;
        double potassiumUptake = SoilParameters.K_PER_KG_DM * dryMatterTurnover * DRY_MATTER_TO_NUTRIENT_KG_PER_HA;

        //    淋洗损失：排水量（%vol）× 养分下限 × 淋洗比例
        double leachedNow = drainagePct * SoilParameters.NUTRIENT_LOW * LEACHED_NITROGEN_FRACTION;

        //    注：施肥量按“本次调用的投加量”整份计入养分池（不按 minutes 折算），
        //    与下面按 factor 折算的 EC 增量刻意保持各自规范给定的形式。
        double nitrogenKgPerHa = Math.max(0.0, base.getNitrogenKgPerHa()
                + doseKgPerHa * FERTILIZER_NITROGEN_SHARE - nitrogenUptake - leachedNow);
        double phosphorusKgPerHa = Math.max(0.0, base.getPhosphorusKgPerHa()
                + doseKgPerHa * FERTILIZER_PHOSPHORUS_SHARE - phosphorusUptake);
        double potassiumKgPerHa = Math.max(0.0, base.getPotassiumKgPerHa()
                + doseKgPerHa * FERTILIZER_POTASSIUM_SHARE - potassiumUptake);
        double leachedNitrogenKgPerHa = base.getLeachedNitrogenKgPerHa() + leachedNow;

        // 6) 盐分平衡：肥料带入盐分，排水与淋洗带走盐分，EC 不低于下限
        double ecDsPerM = base.getEcDsPerM() + doseKgPerHa * SoilParameters.EC_PER_KG_FERT * factor;
        ecDsPerM -= SoilParameters.EC_LEACH_RATE * drainagePct * factor;
        ecDsPerM = Math.max(EC_FLOOR_DS_PER_M, ecDsPerM - SoilParameters.EC_LEACH_RATE * factor);

        // 7) 土壤 pH：肥料微酸化，灌溉水向 6.5 附近微抬升，限幅在适宜区间
        double soilPh = clamp(base.getSoilPh()
                        - doseKgPerHa * PH_ACIDIFICATION_PER_KG_FERTILIZER
                        + irrigationMm * PH_RAISE_PER_MM_IRRIGATION,
                SoilParameters.PH_MIN, SoilParameters.PH_MAX);

        // 8) 养分供应因子：由土壤速效氮相对丰缺阈值线性换算
        double nutrientFactor = clamp(
                (nitrogenKgPerHa - SoilParameters.NUTRIENT_LOW) / (SoilParameters.NUTRIENT_HIGH - SoilParameters.NUTRIENT_LOW),
                0.3, 1.0);

        // 9) 累计灌溉量（mm）
        double irrigationMmTotal = base.getIrrigationMmTotal() + irrigationMm;

        return new SoilState(soilMoisturePct, ecDsPerM, soilPh, nitrogenKgPerHa, phosphorusKgPerHa,
                potassiumKgPerHa, leachedNitrogenKgPerHa, irrigationMmTotal, nutrientFactor);
    }

    /**
     * 按生育阶段取作物系数 Kc（FAO-56 番茄作物系数族）。
     *
     * <p>苗期 → {@code KC_INITIAL}；开花/坐果期 → {@code KC_MID}；
     * 果实膨大/成熟期 → {@code KC_LATE}；{@code crop} 为 {@code null} 时取 {@code KC_MID}。</p>
     */
    private double cropCoefficient(TomatoCropState crop) {
        if (crop == null) {
            return SoilParameters.KC_MID;
        }
        CropStage stage = crop.getStage();
        if (stage == null) {
            return SoilParameters.KC_MID;
        }
        switch (stage) {
            case SEEDLING:
                return SoilParameters.KC_INITIAL;
            case FLOWERING:
            case FRUIT_SET:
                return SoilParameters.KC_MID;
            case FRUIT_GROWTH:
            case MATURITY:
                return SoilParameters.KC_LATE;
            default:
                return SoilParameters.KC_MID;
        }
    }

    /** 区间限幅。 */
    private static double clamp(double value, double lower, double upper) {
        return Math.max(lower, Math.min(upper, value));
    }
}
