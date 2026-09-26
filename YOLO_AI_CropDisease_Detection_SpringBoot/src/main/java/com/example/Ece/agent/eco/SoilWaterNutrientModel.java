package com.example.Ece.agent.eco;

import com.example.Ece.agent.crop.CropStage;
import com.example.Ece.agent.crop.TomatoCropState;
import com.example.Ece.agent.crop.TomatoGrowthParameters;
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

    /**
     * 根区 1 mm 水量对应的体积含水率变化（%vol/mm）。
     *
     * <p><b>2026-09-26 由 1.6 改为 0.4。</b>按注释的定义（1 mm 水量摊到根区深度上的体积占比），
     * 0.25 m 有效根深对应 {@code 1 mm / 250 mm = 0.4 %}。原值 1.6 相当于假设 62.5 cm 根深，
     * 与项目自身参数（{@code TomatoSimulationEngine} 的 0.25 m 有效根深、
     * 60 L → 0.168 个百分点）**相差 4 倍**。改后与引擎口径一致：60 L / 142.8 m² = 0.42 mm
     * × 0.4 = 0.168 个百分点，与引擎的换算完全相同。</p>
     */
    private static final double MM_TO_PCT_VOL = 0.4;

    /** 简化 Hargreaves 式系数。 */
    private static final double HARGREAVES_COEFFICIENT = 0.0023;

    /** 简化 Hargreaves 式的温度偏移（摄氏度）。 */
    private static final double HARGREAVES_TEMPERATURE_OFFSET_C = 17.8;

    /** 太阳常数（MJ·m^-2·min^-1），FAO-56 式 21 用。 */
    private static final double SOLAR_CONSTANT_MJ_M2_MIN = 0.0820;

    /** 辐射项换算系数（MJ·m^-2·d^-1 -> mm/d）。 */
    private static final double ET0_RADIATION_COEFFICIENT = 0.408;

    /** 含水率下限系数（相对凋萎点）。 */
    private static final double MOISTURE_FLOOR_RATIO = 0.5;

    /** 含水率上限余量（相对田间持水量，%vol）。 */
    private static final double MOISTURE_HEADROOM_PCT = 5.0;

    /** 排水淋洗氮占“排水量 × 养分下限”的比例。 */
    private static final double LEACHED_NITROGEN_FRACTION = 0.01;

    /** 复合肥中磷的分配比例。 */
    private static final double FERTILIZER_PHOSPHORUS_SHARE = 0.07;

    /** 复合肥中钾的分配比例。 */
    private static final double FERTILIZER_POTASSIUM_SHARE = 0.20;



    /** 每 kg/ha 肥料造成的 pH 下降（酸化）。 */
    private static final double PH_ACIDIFICATION_PER_KG_FERTILIZER = 0.0005;

    /** 每 mm 灌溉水造成的 pH 抬升（灌溉水接近中性，把偏酸土壤向 6.5 附近抬升）。 */
    private static final double PH_RAISE_PER_MM_IRRIGATION = 0.002;

    /** EC 下限（dS/m）。 */
    private static final double EC_FLOOR_DS_PER_M = 0.3;

    /** 环境缺失时的默认温度（摄氏度）。 */
    private static final double DEFAULT_TEMPERATURE_C = 22.0;

    /** 环境缺失时的默认光强（PPFD）。 */
    /** environment 或其模拟时间为空时的日序回退值（9 月 21 日，与评测起始日一致）。 */
    private static final int DEFAULT_DAY_OF_YEAR = 264;

    /**
     * 初始土壤状态：适宜含水率、低盐、中性偏酸、养分充足。
     *
     * @return 初始土壤状态
     */
    public SoilState initial() {
        return new SoilState(62.0, 1.2, 6.2, 90.0, 45.0, 140.0, 0.0, 0.0, 1.0);
    }

    /**
     * 逐生育期**累计**养分吸收比例（0~1），由 GDD 分段线性插值。
     *
     * <p>锚点来自褚屿等（2021）的四个采样期，按本模型的 GDD 阈值对齐：
     * 开花期末（{@code GDD_FLOWERING} 600~{@code GDD_FRUIT_SET} 800 之间取 800）对应 18.68%、
     * 坐果后期（{@code GDD_FRUIT_GROWTH} 1200）对应 68.08%、成熟（{@code GDD_MATURITY} 1500）对应 100%。
     * 之后维持 100%（原文未测成熟后的吸收，取其终点）。</p>
     *
     * <p><b>两处属本项目假设，需与论文数据分开看</b>：① GDD 0~800 段由 0 线性升到 18.68%——
     * 原文只给了采样点、没给段内形状；② 把论文的"开花期"采样点对齐到本模型的 GDD 800。
     * 这两条都写在 {@link com.example.Ece.agent.eco.SoilParameters} 的常量注释里。</p>
     */
    static double cumulativeNutrientShare(double gdd) {
        if (gdd <= 0.0) {
            return 0.0;
        }
        if (gdd <= TomatoGrowthParameters.GDD_FRUIT_SET) {
            return SoilParameters.NUTRIENT_CUMULATIVE_SHARE_AT_FLOWERING * gdd / TomatoGrowthParameters.GDD_FRUIT_SET;
        }
        if (gdd <= TomatoGrowthParameters.GDD_FRUIT_GROWTH) {
            double span = TomatoGrowthParameters.GDD_FRUIT_GROWTH - TomatoGrowthParameters.GDD_FRUIT_SET;
            return SoilParameters.NUTRIENT_CUMULATIVE_SHARE_AT_FLOWERING
                    + (SoilParameters.NUTRIENT_CUMULATIVE_SHARE_AT_LATE_FRUIT_SET
                            - SoilParameters.NUTRIENT_CUMULATIVE_SHARE_AT_FLOWERING)
                            * (gdd - TomatoGrowthParameters.GDD_FRUIT_SET) / span;
        }
        if (gdd <= TomatoGrowthParameters.GDD_MATURITY) {
            double span = TomatoGrowthParameters.GDD_MATURITY - TomatoGrowthParameters.GDD_FRUIT_GROWTH;
            return SoilParameters.NUTRIENT_CUMULATIVE_SHARE_AT_LATE_FRUIT_SET
                    + (1.0 - SoilParameters.NUTRIENT_CUMULATIVE_SHARE_AT_LATE_FRUIT_SET)
                            * (gdd - TomatoGrowthParameters.GDD_FRUIT_GROWTH) / span;
        }
        return 1.0;
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

        // 1) 参考蒸散 ET0：FAO-56 Hargreaves 式（mm/day）
        //
        //    ET0 = 0.0023 × (Tmean + 17.8) × (Tmax − Tmin)^0.5 × Ra × 0.408
        //
        //    2026-09-26 修：原实现把 `(Tmax − Tmin)^0.5 × Ra` 整项替换成了无量纲的
        //    `sqrt(ppfd/100)`（量级 0.3~3），却**保留了 0.408 换算系数**——而 Ra 的量级是
        //    10~40 MJ·m^-2·d^-1。少了 Ra 这一项，ET0 算出来约 0.065 mm/day，
        //    而该式的正常量级是 3~6 mm/day，**小了约 50 倍**。后果是土壤模型几乎不耗水。
        //    现按 FAO-56 式 21~25 计算 Ra（需要纬度与日序）。
        int dayOfYear = environment == null || environment.getSimulatedAt() == null
                ? DEFAULT_DAY_OF_YEAR : environment.getSimulatedAt().getDayOfYear();
        double radiationRa = extraterrestrialRadiationMj(SoilParameters.LATITUDE_DEGREES, dayOfYear);
        double et0MmPerDay = HARGREAVES_COEFFICIENT * (temperatureC + HARGREAVES_TEMPERATURE_OFFSET_C)
                * Math.sqrt(SoilParameters.DAILY_TEMPERATURE_RANGE_C)
                * radiationRa * ET0_RADIATION_COEFFICIENT;

        // 2) 作物蒸散 ETc = Kc × ET0 × 时间片（mm/本步）
        double kc = cropCoefficient(crop);
        double etcMm = kc * et0MmPerDay * factor;

        // 3) 灌溉量（mm/本步）
        //    2026-09-26 修：`IRRIGATION_MM_PER_STEP` 名字说"每步"、注释说"mm/day"，自相矛盾；
        //    且与引擎各浇各的（引擎按 60 L/次 = 0.42 mm）。现统一用由引擎推出的**每次事件水量**，
        //    不再乘时间片——因为它是每次事件的量，不是速率。
        double irrigationMm = irrigationOn ? SoilParameters.IRRIGATION_MM_PER_EVENT : 0.0;

        // 4) 水量平衡：mm 换算为体积含水率变化，超出田间持水量的部分视为向下排水
        double deltaPct = (irrigationMm - etcMm) * MM_TO_PCT_VOL;
        double rawMoisturePct = base.getSoilMoisturePct() + deltaPct;
        double drainagePct = Math.max(0.0, rawMoisturePct - SoilParameters.FIELD_CAPACITY_PCT);
        double soilMoisturePct = clamp(rawMoisturePct - drainagePct,
                SoilParameters.WILTING_POINT_PCT * MOISTURE_FLOOR_RATIO,
                SoilParameters.FIELD_CAPACITY_PCT + MOISTURE_HEADROOM_PCT);

        // 5) 养分平衡：矿化 + 施肥 - 作物带走 - 排水淋洗
        //
        //    2026-09-26 换掉需求侧。原实现按“总干重 × 2%/天 × 换算系数”估当步带走量，
        //    该式**随作物生长而放大**，等于“供多少就带走多少”——供给与需求由同一个变量驱动，
        //    两端一起动，氮收支永远收敛不到有意义的平衡（实测补上矿化项后季末仍为 0）。
        //
        //    现按**目标产量 × 每吨带走量 × 逐生育期累积比例**定需求：
        //      · 目标产量与每吨带走量出自李书田等 2022（设施番茄 n=703，QUEFTS 最佳需求）；
        //      · 逐生育期累积比例出自褚屿等 2021（开花 18.68% / 坐果后期 68.08% / 成熟 100%）。
        //    需求只随 **GDD** 前进，与土壤供给和作物生物量都无关，因此两端解耦。
        //    用累积曲线而非瞬时速率：原文给的就是累积量，且这样不需要每步的 GDD 增量。
        double removalPerHa = SoilParameters.TARGET_MARKETABLE_YIELD_KG_PER_HA / 1000.0;
        double cumulativeShare = crop == null ? 0.0 : cumulativeNutrientShare(crop.getGdd());
        double targetNitrogenUptake = removalPerHa * SoilParameters.NUTRIENT_REMOVAL_N_KG_PER_TONNE * cumulativeShare;

        // 本步带走量 = 目标累计量 - 已累计量。上一次调用已把 base 的累计量置为当时的目标值，
        // 因此这里不会重复计账；GDD 停涨（如生长受抑）时目标不再前进，带走量自然归零。
        double nitrogenUptake = Math.max(0.0, targetNitrogenUptake - base.getNitrogenUptakeKgPerHa());
        double nitrogenUptakeCumulative = Math.max(base.getNitrogenUptakeKgPerHa(), targetNitrogenUptake);

        // 磷钾与氮走**同一条累积曲线**（同一份逐期比例数据），故按每吨带走量的固定比例由氮推出，
        // 不必各自再存一个累计字段——三个累计量本可以互相换算，分头维护只会给出不一致的机会。
        double phosphorusUptake = nitrogenUptake
                * (SoilParameters.NUTRIENT_REMOVAL_P2O5_KG_PER_TONNE / SoilParameters.NUTRIENT_REMOVAL_N_KG_PER_TONNE);
        double potassiumUptake = nitrogenUptake
                * (SoilParameters.NUTRIENT_REMOVAL_K2O_KG_PER_TONNE / SoilParameters.NUTRIENT_REMOVAL_N_KG_PER_TONNE);

        //    淋洗损失：排水量（%vol）× 养分下限 × 淋洗比例
        double leachedNow = drainagePct * SoilParameters.NUTRIENT_LOW * LEACHED_NITROGEN_FRACTION;

        //    矿化供氮：土壤有机氮分解释放的无机氮，按日速率 × 本步天数计入。
        //    2026-09-26 新增——此前氮池**只有施肥一个输入**，于是季内必然见底。
        //    未做温度响应（见 SoilParameters 的说明）。
        double mineralizedNow = SoilParameters.MINERALIZATION_KG_PER_HA_PER_DAY * factor;

        //    注：施肥量按“本次调用的投加量”整份计入养分池（不按 minutes 折算），
        //    与下面按 factor 折算的 EC 增量刻意保持各自规范给定的形式。
        double nitrogenKgPerHa = Math.max(0.0, base.getNitrogenKgPerHa()
                + mineralizedNow + doseKgPerHa * SoilParameters.FERTILIZER_NITROGEN_SHARE
                - nitrogenUptake - leachedNow);
        double phosphorusKgPerHa = Math.max(0.0, base.getPhosphorusKgPerHa()
                + doseKgPerHa * FERTILIZER_PHOSPHORUS_SHARE - phosphorusUptake);
        double potassiumKgPerHa = Math.max(0.0, base.getPotassiumKgPerHa()
                + doseKgPerHa * FERTILIZER_POTASSIUM_SHARE - potassiumUptake);
        double leachedNitrogenKgPerHa = base.getLeachedNitrogenKgPerHa() + leachedNow;

        // 6) 盐分平衡：肥料带入盐分，排水与淋洗带走盐分，EC 不低于下限
        //
        // 2026-09-26 修：原实现扣了**两次**——先按排水比例扣一次，又无条件再扣一次
        // `- EC_LEACH_RATE * factor`。后果是排水量为 0 时 EC 也每步下降，**必然触底**：
        // 实测 EC 从 1.150 掉到下限 0.300 并在第 20 天后锁死，盐分平衡形同虚设。
        // 现在只保留按排水比例的那一项——没有排水就没有淋洗，这才是有物理含义的形式。
        double ecDsPerM = base.getEcDsPerM() + doseKgPerHa * SoilParameters.EC_PER_KG_FERT * factor;
        ecDsPerM -= SoilParameters.EC_LEACH_RATE * drainagePct * factor;
        ecDsPerM = Math.max(EC_FLOOR_DS_PER_M, ecDsPerM);

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
                potassiumKgPerHa, leachedNitrogenKgPerHa, irrigationMmTotal, nutrientFactor,
                nitrogenUptakeCumulative);
    }

    /**
     * 按生育阶段取作物系数 Kc（FAO-56 番茄作物系数族）。
     *
     * <p>苗期 → {@code KC_INITIAL}；开花/坐果期 → {@code KC_MID}；
     * 果实膨大/成熟期 → {@code KC_LATE}；{@code crop} 为 {@code null} 时取 {@code KC_MID}。</p>
     */
    /**
     * 日序天文辐射 Ra（MJ·m⁻²·d⁻¹），FAO-56 式 21~25。
     *
     * <p>这是修正 ET0 的关键项：原实现漏掉的正是它。30.7°N 在秋分前后算得约 32 MJ·m⁻²·d⁻¹，
     * 与教科书量级一致；代入 Hargreaves 式得 ET0 ≈ 4 mm/day，落进 3~6 mm/day 的正常区间。</p>
     *
     * <p>公式（φ 为纬度，J 为日序，全部角度用弧度）：
     * <pre>
     *   dr = 1 + 0.033·cos(2πJ/365)                        日地距离倒数
     *   δ  = 0.409·sin(2πJ/365 − 1.39)                     太阳赤纬
     *   ωs = arccos(−tan φ · tan δ)                        日落时角
     *   Ra = (24·60/π)·Gsc·dr·[ωs·sin φ·sin δ + cos φ·cos δ·sin ωs]
     * </pre>
     * 出处：{@code docs/tomato-greenhouse-agent.md} 已登记的 FAO-56 第 3 章
     * （https://www.fao.org/4/X0490E/x0490e07.htm）。</p>
     */
    private double extraterrestrialRadiationMj(double latitudeDegrees, int dayOfYear) {
        double phi = Math.toRadians(latitudeDegrees);
        double dr = 1.0 + 0.033 * Math.cos(2.0 * Math.PI * dayOfYear / 365.0);
        double declination = 0.409 * Math.sin(2.0 * Math.PI * dayOfYear / 365.0 - 1.39);
        // 极昼/极夜时 −tanφ·tanδ 会越出 [-1,1]，acos 定义域外会得到 NaN，故先钳位。
        double sunsetCos = Math.max(-1.0, Math.min(1.0, -Math.tan(phi) * Math.tan(declination)));
        double sunsetAngle = Math.acos(sunsetCos);
        return (24.0 * 60.0 / Math.PI) * SOLAR_CONSTANT_MJ_M2_MIN * dr
                * (sunsetAngle * Math.sin(phi) * Math.sin(declination)
                + Math.cos(phi) * Math.cos(declination) * Math.sin(sunsetAngle));
    }

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
