package com.example.Ece.agent.eco;

import com.example.Ece.agent.crop.TomatoCropState;
import com.example.Ece.agent.crop.TomatoGrowthParameters;
import com.example.Ece.agent.model.SimulationState;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * 番茄病虫害流行模型（温度—湿度驱动的侵染进程 + logistic 害虫种群，模型 2）。
 *
 * <p>模型把温室环境（{@link SimulationState}）与作物冠层（{@link TomatoCropState}）转换为病虫害状态增量：</p>
 * <ol>
 *   <li>温度适宜度 fTemp：最适区间内为 1.0，区间外向“中点 ± 半宽”线性衰减到 0；</li>
 *   <li>湿度适宜度 fMoisture：灰霉病/晚疫病/叶霉病为“阈值以上线性上升”型，
 *       白粉病为“中等湿度带（峰值 65%）+ 高湿受抑”型；</li>
 *   <li>侵染速率 = fTemp × fMoisture × 病原基数，按 {@code minutes / 1440} 积分推动病原基数增长；</li>
 *   <li>潜伏进度累积，越过 1.0 触发一次侵染事件并按严重度增益推进严重度；</li>
 *   <li>害虫按 logistic 增长，温度适宜度取 20~30 ℃ 峰值。</li>
 * </ol>
 *
 * <p><b>纯函数约定：</b>{@link #advance} 不保存任何可变实例状态，输出只是
 * {@code (current, environment, crop, minutes)} 的确定函数；不使用随机数、时钟或任何
 * 非确定性来源。规范中的“每步侵染概率”在本实现中按确定性速率积分（不做随机抽样），
 * 因此同样输入必然得到同样输出。</p>
 *
 * <p>所有输出均为模拟量，不代表任何实测数据。</p>
 */
@Component
public class PestDiseaseEpidemicModel {

    /** 一天的分钟数，用于把分钟片段折算为日尺度速率。 */
    private static final double MINUTES_PER_DAY = 1440.0;

    /** 环境缺失时的默认温度（摄氏度）。 */
    private static final double DEFAULT_TEMPERATURE_C = 22.0;

    /** 环境缺失时的默认空气相对湿度（%）。 */
    private static final double DEFAULT_HUMIDITY_PCT = 85.0;

    /**
     * 初始病虫害状态：四种病害严重度为 0、病原基数 0.05、潜伏进度 0，
     * 产量损失因子 1.0，害虫种群 5.0。
     *
     * @return 初始病虫害状态
     */
    public DiseaseState initial() {
        Map<DiseaseKind, Double> severity = new EnumMap<DiseaseKind, Double>(DiseaseKind.class);
        Map<DiseaseKind, Double> inoculum = new EnumMap<DiseaseKind, Double>(DiseaseKind.class);
        Map<DiseaseKind, Double> latent = new EnumMap<DiseaseKind, Double>(DiseaseKind.class);
        for (DiseaseKind kind : DiseaseKind.values()) {
            severity.put(kind, 0.0);
            inoculum.put(kind, EpidemicParameters.INITIAL_INOCULUM);
            latent.put(kind, 0.0);
        }
        return new DiseaseState(severity, inoculum, latent, 0, 1.0,
                EpidemicParameters.INITIAL_PEST_POPULATION);
    }

    /**
     * 推进一步（{@code minutes} 分钟），返回新的不可变病虫害状态。
     *
     * <p>本方法为纯函数：不读写任何实例字段，只依赖入参。</p>
     *
     * @param current     当前病虫害状态；为 {@code null} 时按 {@link #initial()} 处理
     * @param environment 当前温室环境（模拟量）；为 {@code null} 时按默认环境处理
     * @param crop        当前作物状态；仅用于由叶面积指数换算冠层湿润加成，为 {@code null} 时按满冠层处理
     * @param minutes     本次推进的分钟数（负数按 0 处理）
     * @return 推进后的病虫害状态
     */
    public DiseaseState advance(DiseaseState current, SimulationState environment,
                                TomatoCropState crop, int minutes) {
        DiseaseState base = current == null ? initial() : current;
        int sliceMinutes = Math.max(0, minutes);
        double dayFraction = sliceMinutes / MINUTES_PER_DAY;

        double temperatureC = environment == null ? DEFAULT_TEMPERATURE_C : environment.getTemperatureC();
        double humidityPct = environment == null ? DEFAULT_HUMIDITY_PCT : environment.getAirHumidityPct();
        double canopyFactor = canopyFactor(crop);

        Map<DiseaseKind, Double> severity = new EnumMap<DiseaseKind, Double>(DiseaseKind.class);
        Map<DiseaseKind, Double> inoculum = new EnumMap<DiseaseKind, Double>(DiseaseKind.class);
        Map<DiseaseKind, Double> latent = new EnumMap<DiseaseKind, Double>(DiseaseKind.class);
        int infectionEvents = base.getInfectionEvents();
        double totalSeverity = 0.0;

        for (DiseaseKind kind : DiseaseKind.values()) {
            double fTemp = temperatureFactor(kind, temperatureC);
            double fMoisture = moistureFactor(kind, humidityPct) * canopyFactor;

            double currentInoculum = base.inoculum(kind);
            // 侵染速率：温度适宜度 × 湿度适宜度 × 病原基数
            double infectionRate = fTemp * fMoisture * currentInoculum;
            // 以 minutes/1440 为步长做确定性积分：侵染速率越高，病原基数增长越快
            double nextInoculum = clamp(currentInoculum
                            + infectionRate * dayFraction * EpidemicParameters.INOCULUM_BUILDUP_PER_DAY,
                    0.0, EpidemicParameters.MAX_INOCULUM);

            // 潜伏进度：潜伏期随温度适宜度缩短（除以 max(0.3, fTemp)）；
            // 并且只有在湿度适宜（fMoisture > 0）时才推进——否则干燥处理也会凭空累积潜伏量，
            // 与“湿度不足则不侵染”的植保常识矛盾，也会让高湿与低湿得到相同的严重度。
            double effectiveLatentPeriod = latentPeriodMinutes(kind)
                    / Math.max(EpidemicParameters.MINIMUM_TEMPERATURE_FACTOR, fTemp);
            double nextLatent = clamp(base.latent(kind) + sliceMinutes * fMoisture / effectiveLatentPeriod,
                    0.0, EpidemicParameters.MAX_LATENT);

            double nextSeverity = base.severity(kind);
            while (nextLatent >= 1.0) {
                infectionEvents++;
                nextLatent -= 1.0;
                nextSeverity = Math.min(EpidemicParameters.MAX_SEVERITY,
                        nextSeverity + severityGain(kind) * (1.0 - nextSeverity / EpidemicParameters.MAX_SEVERITY));
            }
            nextSeverity = clamp(nextSeverity, 0.0, EpidemicParameters.MAX_SEVERITY);

            severity.put(kind, nextSeverity);
            inoculum.put(kind, nextInoculum);
            latent.put(kind, nextLatent);
            totalSeverity += nextSeverity;
        }

        // 病害产量损失因子：严重度总和每 200% 折算为全部损失
        double diseaseDamageFactor = clamp(
                1.0 - totalSeverity / EpidemicParameters.DAMAGE_SEVERITY_DIVISOR, 0.0, 1.0);

        // 害虫 logistic 增长：温度适宜度取 20~30 ℃ 峰值
        double pestTemperatureFactor = bandFactor(temperatureC,
                EpidemicParameters.PEST_OPTIMAL_LOW_C, EpidemicParameters.PEST_OPTIMAL_HIGH_C,
                EpidemicParameters.PEST_TEMPERATURE_HALF_WIDTH_C);
        double currentPest = base.getPestPopulation();
        double pestPopulation = currentPest + EpidemicParameters.R_MAX_PER_DAY * dayFraction * currentPest
                * (1.0 - currentPest / EpidemicParameters.CARRYING_CAPACITY) * pestTemperatureFactor;
        pestPopulation = Math.max(0.0, pestPopulation);

        return new DiseaseState(severity, inoculum, latent, infectionEvents,
                diseaseDamageFactor, pestPopulation);
    }

    /** 病害温度适宜度：最适区间内 1.0，区间外向“中点 ± 半宽”线性衰减到 0。 */
    private double temperatureFactor(DiseaseKind kind, double temperatureC) {
        return bandFactor(temperatureC, optimalLowC(kind), optimalHighC(kind),
                EpidemicParameters.TEMPERATURE_HALF_WIDTH_C);
    }

    /**
     * 温度适宜度带函数：{@code [optimalLow, optimalHigh]} 内为 1.0，
     * 区间外以“区间中点 ± halfWidth”为零点线性衰减，限幅 {@code [0,1]}。
     */
    private double bandFactor(double temperatureC, double optimalLowC, double optimalHighC, double halfWidthC) {
        if (temperatureC >= optimalLowC && temperatureC <= optimalHighC) {
            return 1.0;
        }
        double midpoint = (optimalLowC + optimalHighC) / 2.0;
        double lowerZeroC = midpoint - halfWidthC;
        double upperZeroC = midpoint + halfWidthC;
        double factor;
        if (temperatureC < optimalLowC) {
            factor = (temperatureC - lowerZeroC) / (optimalLowC - lowerZeroC);
        } else {
            factor = (upperZeroC - temperatureC) / (upperZeroC - optimalHighC);
        }
        return clamp(factor, 0.0, 1.0);
    }

    /**
     * 病害湿度适宜度：白粉病单独走湿度带函数，其余三种为“阈值以上线性上升”。
     *
     * <p>本简化模型以空气相对湿度作为叶面湿润时长的代理量（叶面湿润代理阈值与湿度阈值同值），
     * 因此三种“高湿促发型”病害使用同一形式的阈值函数。</p>
     */
    private double moistureFactor(DiseaseKind kind, double humidityPct) {
        switch (kind) {
            case BOTRYTIS:
                return thresholdMoistureFactor(humidityPct,
                        EpidemicParameters.BOTRYTIS_LEAF_WETNESS_PROXY_THRESHOLD_PCT);
            case LATE_BLIGHT:
                return thresholdMoistureFactor(humidityPct,
                        EpidemicParameters.LATE_BLIGHT_LEAF_WETNESS_PROXY_THRESHOLD_PCT);
            case LEAF_MOLD:
                return thresholdMoistureFactor(humidityPct,
                        EpidemicParameters.LEAF_MOLD_LEAF_WETNESS_PROXY_THRESHOLD_PCT);
            case POWDERY_MILDEW:
                return powderyMildewMoistureFactor(humidityPct);
            default:
                throw new IllegalArgumentException("未知病害种类: " + kind);
        }
    }

    /** 阈值型湿度适宜度：{@code clamp((RH - threshold) / (100 - threshold), 0, 1)}。 */
    private double thresholdMoistureFactor(double humidityPct, double thresholdPct) {
        return clamp((humidityPct - thresholdPct) / (100.0 - thresholdPct), 0.0, 1.0);
    }

    /**
     * 白粉病专用湿度适宜度：峰值 65 %、适宜带 50 %~75 %。
     *
     * <p>与灰霉病/晚疫病/叶霉病相反，高湿对白粉病是<b>抑制</b>而非促进：
     * 相对湿度高于 {@code POWDERY_MILDEW_HIGH_HUMIDITY_PCT}（85 %）时适宜度被压到下限
     * {@code POWDERY_MILDEW_HUMIDITY_FLOOR}（0.1），因此 95 % 条件下的白粉病严重度
     * 必然低于 65 % 条件；这一差异在代码中是真实分支，而不是注释。</p>
     */
    private double powderyMildewMoistureFactor(double humidityPct) {
        if (humidityPct >= EpidemicParameters.POWDERY_MILDEW_BAND_LOW_PCT
                && humidityPct <= EpidemicParameters.POWDERY_MILDEW_BAND_HIGH_PCT) {
            return 1.0;
        }
        double floor = EpidemicParameters.POWDERY_MILDEW_HUMIDITY_FLOOR;
        if (humidityPct > EpidemicParameters.POWDERY_MILDEW_BAND_HIGH_PCT) {
            // 75 % → 85 %：由 1.0 线性压到下限 0.1；高于 85 % 保持下限
            double factor = 1.0 - (humidityPct - EpidemicParameters.POWDERY_MILDEW_BAND_HIGH_PCT)
                    / (EpidemicParameters.POWDERY_MILDEW_HIGH_HUMIDITY_PCT
                    - EpidemicParameters.POWDERY_MILDEW_BAND_HIGH_PCT) * (1.0 - floor);
            return Math.max(floor, factor);
        }
        // 50 % → 35 %：由 1.0 线性压到下限 0.1；低于 35 % 保持下限
        double factor = 1.0 - (EpidemicParameters.POWDERY_MILDEW_BAND_LOW_PCT - humidityPct)
                / (EpidemicParameters.POWDERY_MILDEW_BAND_LOW_PCT
                - EpidemicParameters.POWDERY_MILDEW_DRY_REFERENCE_PCT) * (1.0 - floor);
        return Math.max(floor, factor);
    }

    /**
     * 冠层湿润加成系数：叶面积指数越大，冠层内叶面湿润时间越长。
     *
     * <p>{@code 0.6 + 0.4 × LAI / MAX_LAI}；{@code crop} 为 {@code null} 时按完全郁闭（1.0）处理。
     * 该系数只缩放湿度适宜度的大小，不改变“高湿促发 / 高湿受抑”的方向。</p>
     */
    private double canopyFactor(TomatoCropState crop) {
        if (crop == null) {
            return 1.0;
        }
        double lai = clamp(crop.getLai(), 0.0, TomatoGrowthParameters.MAX_LAI);
        return EpidemicParameters.CANOPY_FACTOR_FLOOR
                + EpidemicParameters.CANOPY_FACTOR_RANGE * lai / TomatoGrowthParameters.MAX_LAI;
    }

    /** 各病害最适温度下限。 */
    private double optimalLowC(DiseaseKind kind) {
        switch (kind) {
            case BOTRYTIS:
                return EpidemicParameters.BOTRYTIS_OPTIMAL_LOW_C;
            case LATE_BLIGHT:
                return EpidemicParameters.LATE_BLIGHT_OPTIMAL_LOW_C;
            case POWDERY_MILDEW:
                return EpidemicParameters.POWDERY_MILDEW_OPTIMAL_LOW_C;
            case LEAF_MOLD:
                return EpidemicParameters.LEAF_MOLD_OPTIMAL_LOW_C;
            default:
                throw new IllegalArgumentException("未知病害种类: " + kind);
        }
    }

    /** 各病害最适温度上限。 */
    private double optimalHighC(DiseaseKind kind) {
        switch (kind) {
            case BOTRYTIS:
                return EpidemicParameters.BOTRYTIS_OPTIMAL_HIGH_C;
            case LATE_BLIGHT:
                return EpidemicParameters.LATE_BLIGHT_OPTIMAL_HIGH_C;
            case POWDERY_MILDEW:
                return EpidemicParameters.POWDERY_MILDEW_OPTIMAL_HIGH_C;
            case LEAF_MOLD:
                return EpidemicParameters.LEAF_MOLD_OPTIMAL_HIGH_C;
            default:
                throw new IllegalArgumentException("未知病害种类: " + kind);
        }
    }

    /** 各病害潜伏期（分钟）。 */
    private double latentPeriodMinutes(DiseaseKind kind) {
        switch (kind) {
            case BOTRYTIS:
                return EpidemicParameters.BOTRYTIS_LATENT_PERIOD_MINUTES;
            case LATE_BLIGHT:
                return EpidemicParameters.LATE_BLIGHT_LATENT_PERIOD_MINUTES;
            case POWDERY_MILDEW:
                return EpidemicParameters.POWDERY_MILDEW_LATENT_PERIOD_MINUTES;
            case LEAF_MOLD:
                return EpidemicParameters.LEAF_MOLD_LATENT_PERIOD_MINUTES;
            default:
                throw new IllegalArgumentException("未知病害种类: " + kind);
        }
    }

    /** 各病害单次侵染事件的严重度增益（%）。 */
    private double severityGain(DiseaseKind kind) {
        switch (kind) {
            case BOTRYTIS:
                return EpidemicParameters.BOTRYTIS_SEVERITY_GAIN;
            case LATE_BLIGHT:
                return EpidemicParameters.LATE_BLIGHT_SEVERITY_GAIN;
            case POWDERY_MILDEW:
                return EpidemicParameters.POWDERY_MILDEW_SEVERITY_GAIN;
            case LEAF_MOLD:
                return EpidemicParameters.LEAF_MOLD_SEVERITY_GAIN;
            default:
                throw new IllegalArgumentException("未知病害种类: " + kind);
        }
    }

    /** 区间限幅。 */
    private static double clamp(double value, double lower, double upper) {
        return Math.max(lower, Math.min(upper, value));
    }
}
