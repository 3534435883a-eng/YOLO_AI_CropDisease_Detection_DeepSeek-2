package com.example.Ece.agent.crop;

/**
 * 番茄作物状态（不可变值对象）。
 *
 * <p>所有字段均为模拟量，<b>不代表任何实测数据</b>。对象一经构造不再改变，
 * 因此 {@link TomatoCropGrowthModel#advance} 是输入的纯函数，便于回放与确定性验证。</p>
 */
public class TomatoCropState {

    private final double gdd;
    private final double lai;
    private final double plantHeightCm;
    private final double wLeaf;
    private final double wStem;
    private final double wRoot;
    private final double wFruit;
    private final double wTotal;
    private final double fruitSetRate;
    private final int fruitCount;
    private final double singleFruitWeightG;
    private final double temperatureFactor;
    private final double co2Factor;
    private final double waterFactor;
    private final CropStage stage;
    private final boolean mature;

    /** 全参构造函数。 */
    public TomatoCropState(double gdd, double lai, double plantHeightCm,
                           double wLeaf, double wStem, double wRoot, double wFruit, double wTotal,
                           double fruitSetRate, int fruitCount, double singleFruitWeightG,
                           double temperatureFactor, double co2Factor, double waterFactor,
                           CropStage stage, boolean mature) {
        this.gdd = gdd;
        this.lai = lai;
        this.plantHeightCm = plantHeightCm;
        this.wLeaf = wLeaf;
        this.wStem = wStem;
        this.wRoot = wRoot;
        this.wFruit = wFruit;
        this.wTotal = wTotal;
        this.fruitSetRate = fruitSetRate;
        this.fruitCount = fruitCount;
        this.singleFruitWeightG = singleFruitWeightG;
        this.temperatureFactor = temperatureFactor;
        this.co2Factor = co2Factor;
        this.waterFactor = waterFactor;
        this.stage = stage;
        this.mature = mature;
    }

    /** 累积有效积温（摄氏度·天，GDD）。 */
    public double getGdd() {
        return gdd;
    }

    /** 叶面积指数 LAI（m^2 叶 / m^2 地面）。 */
    public double getLai() {
        return lai;
    }

    /** 株高（cm）。 */
    public double getPlantHeightCm() {
        return plantHeightCm;
    }

    /** 叶片干重（g·m^-2）。 */
    public double getWLeaf() {
        return wLeaf;
    }

    /** 茎干重（g·m^-2）。 */
    public double getWStem() {
        return wStem;
    }

    /** 根系干重（g·m^-2）。 */
    public double getWRoot() {
        return wRoot;
    }

    /** 果实干重（g·m^-2）。 */
    public double getWFruit() {
        return wFruit;
    }

    /** 总干重（g·m^-2），恒等于叶+茎+根+果。 */
    public double getWTotal() {
        return wTotal;
    }

    /** 坐果率（0~1）。 */
    public double getFruitSetRate() {
        return fruitSetRate;
    }

    /** 单株代理果穗数换算得到的果数（固定 20 穗代理）。 */
    public int getFruitCount() {
        return fruitCount;
    }

    /** 单果干重（g）。 */
    public double getSingleFruitWeightG() {
        return singleFruitWeightG;
    }

    /** 温度限制因子（0~1）。 */
    public double getTemperatureFactor() {
        return temperatureFactor;
    }

    /** CO2 施肥因子（1.0~1.35）。 */
    public double getCo2Factor() {
        return co2Factor;
    }

    /** 水分限制因子（0.3~1.0）。 */
    public double getWaterFactor() {
        return waterFactor;
    }

    /** 当前生育阶段。 */
    public CropStage getStage() {
        return stage;
    }

    /** 是否达到成熟期。 */
    public boolean isMature() {
        return mature;
    }
}
