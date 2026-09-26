package com.example.Ece.agent.eco;

/**
 * 土壤水分—养分状态（不可变值对象，模型 1）。
 *
 * <p>所有字段均为模拟量，<b>不代表任何实测数据</b>。对象一经构造不再改变，
 * 因此 {@link SoilWaterNutrientModel#advance} 是输入的纯函数，便于回放与确定性验证。</p>
 */
public class SoilState {

    private final double soilMoisturePct;
    private final double ecDsPerM;
    private final double soilPh;
    private final double nitrogenKgPerHa;
    private final double phosphorusKgPerHa;
    private final double potassiumKgPerHa;
    private final double leachedNitrogenKgPerHa;
    private final double irrigationMmTotal;
    private final double nutrientFactor;

    /**
     * 本季累计的作物吸氮量（kg/hm²）。
     *
     * <p>存在的理由：需求侧现在按**累积吸收曲线**驱动（见 {@code SoilParameters} 的
     * {@code NUTRIENT_CUMULATIVE_SHARE_*}），而"当前该累计到多少"必须与"已经累计了多少"相减
     * 才能得到本步的吸氮量。因此必须有个量记住已吸收的部分。
     * 顺带的好处是它天然防止重复计账——目标值只随 GDD 前进，吸氮量不会随供给反复回补。</p>
     */
    private final double nitrogenUptakeKgPerHa;

    /** 兼容构造：累计吸氮量取 0（季初）。 */
    public SoilState(double soilMoisturePct, double ecDsPerM, double soilPh,
                     double nitrogenKgPerHa, double phosphorusKgPerHa, double potassiumKgPerHa,
                     double leachedNitrogenKgPerHa, double irrigationMmTotal, double nutrientFactor) {
        this(soilMoisturePct, ecDsPerM, soilPh, nitrogenKgPerHa, phosphorusKgPerHa, potassiumKgPerHa,
                leachedNitrogenKgPerHa, irrigationMmTotal, nutrientFactor, 0.0);
    }

    /** 全参构造函数。 */
    public SoilState(double soilMoisturePct, double ecDsPerM, double soilPh,
                     double nitrogenKgPerHa, double phosphorusKgPerHa, double potassiumKgPerHa,
                     double leachedNitrogenKgPerHa, double irrigationMmTotal, double nutrientFactor,
                     double nitrogenUptakeKgPerHa) {
        this.soilMoisturePct = soilMoisturePct;
        this.ecDsPerM = ecDsPerM;
        this.soilPh = soilPh;
        this.nitrogenKgPerHa = nitrogenKgPerHa;
        this.phosphorusKgPerHa = phosphorusKgPerHa;
        this.potassiumKgPerHa = potassiumKgPerHa;
        this.leachedNitrogenKgPerHa = leachedNitrogenKgPerHa;
        this.irrigationMmTotal = irrigationMmTotal;
        this.nutrientFactor = nutrientFactor;
        this.nitrogenUptakeKgPerHa = nitrogenUptakeKgPerHa;
    }

    /** 本季累计作物吸氮量（kg/ha）。 */
    public double getNitrogenUptakeKgPerHa() {
        return nitrogenUptakeKgPerHa;
    }

    /** 土壤含水率（%vol）。 */
    public double getSoilMoisturePct() {
        return soilMoisturePct;
    }

    /** 土壤饱和浸提液电导率 EC（dS/m）。 */
    public double getEcDsPerM() {
        return ecDsPerM;
    }

    /** 土壤 pH。 */
    public double getSoilPh() {
        return soilPh;
    }

    /** 土壤速效氮（kg/ha）。 */
    public double getNitrogenKgPerHa() {
        return nitrogenKgPerHa;
    }

    /** 土壤速效磷（kg/ha）。 */
    public double getPhosphorusKgPerHa() {
        return phosphorusKgPerHa;
    }

    /** 土壤速效钾（kg/ha）。 */
    public double getPotassiumKgPerHa() {
        return potassiumKgPerHa;
    }

    /** 累计淋洗氮量（kg/ha）。 */
    public double getLeachedNitrogenKgPerHa() {
        return leachedNitrogenKgPerHa;
    }

    /** 累计灌溉量（mm）。 */
    public double getIrrigationMmTotal() {
        return irrigationMmTotal;
    }

    /** 养分供应因子（0.3~1.0），由土壤速效氮与丰缺阈值换算。 */
    public double getNutrientFactor() {
        return nutrientFactor;
    }
}
