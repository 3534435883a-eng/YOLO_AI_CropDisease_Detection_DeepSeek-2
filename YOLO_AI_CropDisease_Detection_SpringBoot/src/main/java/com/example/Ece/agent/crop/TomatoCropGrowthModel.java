package com.example.Ece.agent.crop;

import com.example.Ece.agent.model.SimulationState;
import org.springframework.stereotype.Component;

/**
 * 番茄半机理作物生长模型（semi-mechanistic）。
 *
 * <p>模型把温室环境（{@link SimulationState}）转换为作物状态增量：</p>
 * <ol>
 *   <li>有效积温（GDD）驱动物候阶段；</li>
 *   <li>冠层 Beer-Lambert 光截获 × 光能利用效率（RUE）驱动干物质积累；</li>
 *   <li>温度 / CO2 / 水分三个限制因子调制同化速率；</li>
 *   <li>同化物按生育阶段分配到叶、茎、根、果；</li>
 *   <li>坐果率由温度、VPD、同化物供应与累积热时间共同决定。</li>
 * </ol>
 *
 * <p><b>纯函数约定：</b>{@link #advance} 不保存任何可变实例状态，
 * 输出只是 {@code (current, environment, minutes)} 的确定函数；
 * 内部不做按天聚合的缓存，而是把本段 {@code minutes} 的增量按
 * {@code minutes / 1440.0} 折算成“日尺度”速率。模型不使用随机数、
 * 时钟或任何非确定性来源，因此同样输入必然得到同样输出。</p>
 *
 * <p>所有输出均为模拟量，不代表任何实测数据。</p>
 */
@Component
public class TomatoCropGrowthModel {

    /** 一天的分钟数，用于把分钟片段折算为日尺度速率。 */
    private static final double MINUTES_PER_DAY = 1440.0;

    /** 光合有效辐射占入射短波辐射的比例。 */
    private static final double PAR_FRACTION_OF_LIGHT = 0.5;

    /** 坐果的适宜温度下限（摄氏度）。 */
    private static final double FRUIT_SET_TEMP_LOW_C = 15.0;

    /** 坐果的适宜温度上限（摄氏度）。 */
    private static final double FRUIT_SET_TEMP_HIGH_C = 30.0;

    /** 坐果温度因子衰减到下限的高温端（摄氏度）。 */
    private static final double FRUIT_SET_TEMP_HOT_CRITICAL_C = 38.0;

    /** 坐果温度因子衰减到下限的低温端（摄氏度）。 */
    private static final double FRUIT_SET_TEMP_COLD_CRITICAL_C = 8.0;

    /** 坐果温度因子下限。 */
    private static final double FRUIT_SET_TEMP_FLOOR = 0.2;

    /** 坐果 VPD 因子衰减到下限的水汽压亏缺（kPa）。 */
    private static final double VPD_FRUIT_SET_CRITICAL_KPA = 3.5;

    /** 坐果 VPD 因子下限。 */
    private static final double VPD_FRUIT_SET_FLOOR = 0.3;

    /** 同化物供应达到饱和的参考总干重（g·m^-2）。 */
    private static final double ASSIMILATE_REFERENCE_G = 40.0;

    /** 固定 20 穗代理，用于由坐果率换算果数。 */
    private static final double TRUSS_PROXY = 20.0;

    /** 每个 GDD 增量带来的单果干重增长（g）。 */
    private static final double SINGLE_FRUIT_GROWTH_G_PER_GDD = 0.02;

    /** 单果干重上限（g）。 */
    private static final double MAX_SINGLE_FRUIT_WEIGHT_G = 250.0;

    /** 每克叶干重对应的株高增长（cm/g）。 */
    private static final double HEIGHT_CM_PER_G_LEAF = 2.5;

    /** 株高上限（cm）。 */
    private static final double MAX_PLANT_HEIGHT_CM = 400.0;

    /** 土壤水分适宜区间下限（%）。 */
    private static final double SOIL_MOISTURE_LOW = 45.0;

    /** 土壤水分适宜区间上限（%）。 */
    private static final double SOIL_MOISTURE_HIGH = 78.0;

    /** 干旱衰减的参考下限（%），此处水分因子取到下限值。 */
    private static final double SOIL_MOISTURE_DRY_REFERENCE = 20.0;

    /** 干旱/过湿时的水分因子下限。 */
    private static final double SOIL_MOISTURE_DRY_FLOOR = 0.3;

    /** 过湿（土壤水分高于适宜上限）时的水分因子。 */
    private static final double SOIL_MOISTURE_WET_FACTOR = 0.9;

    /** CO2 参考浓度（ppm），此浓度下 CO2 因子为 1.0。 */
    private static final double CO2_REFERENCE_PPM = 400.0;

    /** CO2 施肥因子的最大增益。 */
    private static final double CO2_MAX_BOOST = 0.35;

    /** 营养生长阶段（苗期/开花期）的叶片分配系数。 */
    private static final double VEGETATIVE_LEAF_FRACTION = 0.5;

    /** 营养生长阶段（苗期/开花期）的茎分配系数。 */
    private static final double VEGETATIVE_STEM_FRACTION = 0.3;

    /** 营养生长阶段（苗期/开花期）的根分配系数。 */
    private static final double VEGETATIVE_ROOT_FRACTION = 0.2;

    /** 生殖生长阶段剩余同化物中分配给叶片的比例。 */
    private static final double REPRODUCTIVE_LEAF_FRACTION = 0.3;

    /** 生殖生长阶段剩余同化物中分配给茎的比例。 */
    private static final double REPRODUCTIVE_STEM_FRACTION = 0.25;

    /** 生殖生长阶段剩余同化物中分配给根的比例。 */
    private static final double REPRODUCTIVE_ROOT_FRACTION = 0.45;

    /**
     * 苗期初始状态：极小生物量的幼苗，所有限制因子为 1.0。
     *
     * @return 初始作物状态
     */
    public TomatoCropState initial() {
        double wLeaf = 1.0;
        double wStem = 0.6;
        double wRoot = 0.8;
        double wFruit = 0.0;
        double wTotal = wLeaf + wStem + wRoot + wFruit;
        return new TomatoCropState(0.0, 0.05, 5.0, wLeaf, wStem, wRoot, wFruit, wTotal,
                0.0, 0, 0.0, 1.0, 1.0, 1.0, CropStage.SEEDLING, false);
    }

    /**
     * 推进一步（{@code minutes} 分钟），返回新的不可变状态。
     *
     * <p>本方法为纯函数：不读写任何实例字段，只依赖 {@code current} 与 {@code environment}。</p>
     *
     * @param current     当前作物状态；为 {@code null} 时返回 {@link #initial()}
     * @param environment 当前温室环境（模拟量）；为 {@code null} 时按标准环境处理
     * @param minutes     本次推进的分钟数（负数按 0 处理）
     * @return 推进后的作物状态
     */
    public TomatoCropState advance(TomatoCropState current, SimulationState environment, int minutes) {
        return advance(current, environment, minutes, 1.0);
    }

    /**
     * 带外部胁迫因子的推进：把土壤养分供应因子与病害损失因子耦合进干物质累积
     * （spec §22.2、§22.3）。{@code externalStressFactor} 取 1.0 表示无额外胁迫。
     *
     * @param externalStressFactor 养分因子 × 病害损失因子的乘积，限幅 {@code [0,1]}
     */
    public TomatoCropState advance(TomatoCropState current, SimulationState environment, int minutes,
                                   double externalStressFactor) {
        if (current == null) {
            return initial();
        }
        int sliceMinutes = Math.max(0, minutes);
        double dayFraction = sliceMinutes / MINUTES_PER_DAY;

        double temperatureC = environment == null
                ? TomatoGrowthParameters.OPTIMAL_LOW_C : environment.getTemperatureC();
        double co2Ppm = environment == null ? CO2_REFERENCE_PPM : environment.getCo2Ppm();
        double soilMoisturePct = environment == null ? 60.0 : environment.getSoilMoisturePct();
        double vpdKpa = environment == null ? 0.8 : environment.getVpdKpa();
        double lightPpfd = environment == null ? 0.0 : environment.getLightPpfd();

        // 1) 有效积温：基点温度以下不累积
        double gddIncrement = Math.max(0.0, temperatureC - TomatoGrowthParameters.BASE_TEMPERATURE_C) * dayFraction;
        double gdd = current.getGdd() + gddIncrement;

        // 2) 冠层光截获 -> 干物质增量
        double lai = clamp(current.getLai(), 0.0, TomatoGrowthParameters.MAX_LAI);
        double par = PAR_FRACTION_OF_LIGHT * lightPpfd;
        double parMJ = par * sliceMinutes * 60.0 / 1e6;
        double iAbsorbed = parMJ * (1.0 - Math.exp(-TomatoGrowthParameters.EXTINCTION_K * lai));
        double temperatureFactor = temperatureFactor(temperatureC);
        double co2Factor = co2Factor(co2Ppm);
        double waterFactor = waterFactor(soilMoisturePct);
        double stressFactor = clamp(externalStressFactor, 0.0, 1.0);
        double dW = TomatoGrowthParameters.RUE_G_PER_MJ * iAbsorbed
                * temperatureFactor * co2Factor * waterFactor * stressFactor;

        // 3) 物候阶段：由累积 GDD 推导，且只进不退
        CropStage stage = resolveStage(gdd, current.getStage());

        // 4) 同化物分配：坐果期起按固定比例分配给果实，余量再分给叶/茎/根
        double dLeaf;
        double dStem;
        double dRoot;
        double dFruit;
        if (stage == CropStage.SEEDLING || stage == CropStage.FLOWERING) {
            dLeaf = dW * VEGETATIVE_LEAF_FRACTION;
            dStem = dW * VEGETATIVE_STEM_FRACTION;
            dRoot = dW * VEGETATIVE_ROOT_FRACTION;
            dFruit = 0.0;
        } else {
            dFruit = dW * TomatoGrowthParameters.FRUIT_ALLOCATION_RATIO;
            double remainder = dW * (1.0 - TomatoGrowthParameters.FRUIT_ALLOCATION_RATIO);
            dLeaf = remainder * REPRODUCTIVE_LEAF_FRACTION;
            dStem = remainder * REPRODUCTIVE_STEM_FRACTION;
            dRoot = remainder * REPRODUCTIVE_ROOT_FRACTION;
        }

        double wLeaf = Math.max(0.0, current.getWLeaf() + dLeaf);
        double wStem = Math.max(0.0, current.getWStem() + dStem);
        double wRoot = Math.max(0.0, current.getWRoot() + dRoot);
        double wFruit = Math.max(0.0, current.getWFruit() + dFruit);

        // 5) 叶面积指数：新叶扩展 - 日衰老
        double dLai = TomatoGrowthParameters.SLA_M2_PER_G * dLeaf
                - TomatoGrowthParameters.SENESCENCE_PER_DAY * lai * dayFraction;
        double nextLai = clamp(lai + dLai, 0.0, TomatoGrowthParameters.MAX_LAI);

        // 6) 株高：与新增叶干重联动
        double plantHeightCm = clamp(current.getPlantHeightCm() + dLeaf * HEIGHT_CM_PER_G_LEAF,
                0.0, MAX_PLANT_HEIGHT_CM);

        // 7) 总干重：果实干重不得超过总干重
        double wTotal = wLeaf + wStem + wRoot + wFruit;
        wFruit = Math.min(wFruit, wTotal);
        wTotal = wLeaf + wStem + wRoot + wFruit;

        // 8) 坐果率：温度 × VPD × 同化物供应 × 热时间进程
        //    注：番茄花穗自下而上陆续开花，坐果是一个连续过程而非阈值跳变，
        //    因此这里用 gdd / GDD_FLOWERING（0~1）表示已进入开花的群体比例，
        //    使坐果率成为累积热时间的连续函数（在开花阈值处恰好达到 1.0）。
        double fruitSetFactor = clamp(gdd / TomatoGrowthParameters.GDD_FLOWERING, 0.0, 1.0);
        double fTemp = fruitSetTemperatureFactor(temperatureC);
        double fVpd = fruitSetVpdFactor(vpdKpa);
        double fAssimilate = Math.min(1.0, wTotal / ASSIMILATE_REFERENCE_G);
        double fruitSetRate = clamp(fTemp * fVpd * fAssimilate * fruitSetFactor, 0.0, 1.0);
        int fruitCount = (int) Math.floor(fruitSetRate * TRUSS_PROXY);

        // 9) 单果干重：进入坐果期后随有效积温增长
        double singleFruitWeightG = current.getSingleFruitWeightG();
        if (stage != CropStage.SEEDLING && stage != CropStage.FLOWERING) {
            singleFruitWeightG += gddIncrement * SINGLE_FRUIT_GROWTH_G_PER_GDD;
        }
        singleFruitWeightG = clamp(singleFruitWeightG, 0.0, MAX_SINGLE_FRUIT_WEIGHT_G);

        boolean mature = stage == CropStage.MATURITY;

        return new TomatoCropState(gdd, nextLai, plantHeightCm,
                wLeaf, wStem, wRoot, wFruit, wTotal,
                fruitSetRate, fruitCount, singleFruitWeightG,
                temperatureFactor, co2Factor, waterFactor, stage, mature);
    }

    /**
     * 温度限制因子：最适区间内为 1.0，高温端线性衰减到 0.0（{@code MAX_TEMPERATURE_C}），
     * 低温端线性衰减到 0.0（{@code BASE_TEMPERATURE_C * 0.5}）。
     */
    private double temperatureFactor(double temperatureC) {
        double factor;
        if (temperatureC >= TomatoGrowthParameters.OPTIMAL_LOW_C
                && temperatureC <= TomatoGrowthParameters.OPTIMAL_HIGH_C) {
            factor = 1.0;
        } else if (temperatureC > TomatoGrowthParameters.OPTIMAL_HIGH_C) {
            factor = 1.0 - (temperatureC - TomatoGrowthParameters.OPTIMAL_HIGH_C)
                    / (TomatoGrowthParameters.MAX_TEMPERATURE_C - TomatoGrowthParameters.OPTIMAL_HIGH_C);
        } else {
            double coldLimit = TomatoGrowthParameters.BASE_TEMPERATURE_C * 0.5;
            factor = (temperatureC - coldLimit) / (TomatoGrowthParameters.OPTIMAL_LOW_C - coldLimit);
        }
        return clamp(factor, 0.0, 1.0);
    }

    /** CO2 施肥因子：相对 400 ppm，每 400 ppm 提升 0.35，最大增益 0.35。 */
    private double co2Factor(double co2Ppm) {
        double boost = (co2Ppm - CO2_REFERENCE_PPM) / CO2_REFERENCE_PPM * CO2_MAX_BOOST;
        return 1.0 + Math.min(CO2_MAX_BOOST, Math.max(0.0, boost));
    }

    /** 水分限制因子：适宜区间内为 1.0，偏干线性衰减到 0.3，偏湿取 0.9。 */
    private double waterFactor(double soilMoisturePct) {
        double factor;
        if (soilMoisturePct >= SOIL_MOISTURE_LOW && soilMoisturePct <= SOIL_MOISTURE_HIGH) {
            factor = 1.0;
        } else if (soilMoisturePct < SOIL_MOISTURE_LOW) {
            factor = 1.0 - (SOIL_MOISTURE_LOW - soilMoisturePct)
                    / (SOIL_MOISTURE_LOW - SOIL_MOISTURE_DRY_REFERENCE) * (1.0 - SOIL_MOISTURE_DRY_FLOOR);
        } else {
            factor = SOIL_MOISTURE_WET_FACTOR;
        }
        return clamp(factor, SOIL_MOISTURE_DRY_FLOOR, 1.0);
    }

    /** 坐果的温度因子：[15, 30] 内为 1.0，向两端线性衰减到 0.2。 */
    private double fruitSetTemperatureFactor(double temperatureC) {
        double factor;
        if (temperatureC >= FRUIT_SET_TEMP_LOW_C && temperatureC <= FRUIT_SET_TEMP_HIGH_C) {
            factor = 1.0;
        } else if (temperatureC > FRUIT_SET_TEMP_HIGH_C) {
            factor = 1.0 - (temperatureC - FRUIT_SET_TEMP_HIGH_C)
                    / (FRUIT_SET_TEMP_HOT_CRITICAL_C - FRUIT_SET_TEMP_HIGH_C) * (1.0 - FRUIT_SET_TEMP_FLOOR);
        } else {
            factor = 1.0 - (FRUIT_SET_TEMP_LOW_C - temperatureC)
                    / (FRUIT_SET_TEMP_LOW_C - FRUIT_SET_TEMP_COLD_CRITICAL_C) * (1.0 - FRUIT_SET_TEMP_FLOOR);
        }
        return clamp(factor, FRUIT_SET_TEMP_FLOOR, 1.0);
    }

    /** 坐果的 VPD 因子：不超过 2.0 kPa 时为 1.0，之后线性衰减到 0.3（3.5 kPa）。 */
    private double fruitSetVpdFactor(double vpdKpa) {
        if (vpdKpa <= TomatoGrowthParameters.VPD_FRUIT_SET_LIMIT_KPA) {
            return 1.0;
        }
        double factor = 1.0 - (vpdKpa - TomatoGrowthParameters.VPD_FRUIT_SET_LIMIT_KPA)
                / (VPD_FRUIT_SET_CRITICAL_KPA - TomatoGrowthParameters.VPD_FRUIT_SET_LIMIT_KPA)
                * (1.0 - VPD_FRUIT_SET_FLOOR);
        return clamp(factor, VPD_FRUIT_SET_FLOOR, 1.0);
    }

    /**
     * 由累积有效积温推导生育阶段，并保证阶段不回退。
     *
     * @param gdd          累积有效积温
     * @param currentStage 当前阶段（可能为 {@code null}）
     * @return 本步结束时的阶段（不小于 {@code currentStage}）
     */
    private CropStage resolveStage(double gdd, CropStage currentStage) {
        CropStage computed;
        if (gdd < TomatoGrowthParameters.GDD_FLOWERING) {
            computed = CropStage.SEEDLING;
        } else if (gdd < TomatoGrowthParameters.GDD_FRUIT_SET) {
            computed = CropStage.FLOWERING;
        } else if (gdd < TomatoGrowthParameters.GDD_FRUIT_GROWTH) {
            computed = CropStage.FRUIT_SET;
        } else if (gdd < TomatoGrowthParameters.GDD_MATURITY) {
            computed = CropStage.FRUIT_GROWTH;
        } else {
            computed = CropStage.MATURITY;
        }
        CropStage baseline = currentStage == null ? CropStage.SEEDLING : currentStage;
        return computed.ordinal() < baseline.ordinal() ? baseline : computed;
    }

    /** 区间限幅。 */
    private static double clamp(double value, double lower, double upper) {
        return Math.max(lower, Math.min(upper, value));
    }
}
