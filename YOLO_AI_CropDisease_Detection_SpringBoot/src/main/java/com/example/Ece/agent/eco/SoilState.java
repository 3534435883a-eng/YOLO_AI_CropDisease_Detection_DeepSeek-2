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

    /** 全参构造函数。 */
    public SoilState(double soilMoisturePct, double ecDsPerM, double soilPh,
                     double nitrogenKgPerHa, double phosphorusKgPerHa, double potassiumKgPerHa,
                     double leachedNitrogenKgPerHa, double irrigationMmTotal, double nutrientFactor) {
        this.soilMoisturePct = soilMoisturePct;
        this.ecDsPerM = ecDsPerM;
        this.soilPh = soilPh;
        this.nitrogenKgPerHa = nitrogenKgPerHa;
        this.phosphorusKgPerHa = phosphorusKgPerHa;
        this.potassiumKgPerHa = potassiumKgPerHa;
        this.leachedNitrogenKgPerHa = leachedNitrogenKgPerHa;
        this.irrigationMmTotal = irrigationMmTotal;
        this.nutrientFactor = nutrientFactor;
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
