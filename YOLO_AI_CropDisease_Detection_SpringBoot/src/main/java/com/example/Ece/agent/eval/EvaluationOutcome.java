package com.example.Ece.agent.eval;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** 单档策略的评测结果：核心指标 + 逐日序列（供前端画曲线与数字孪生播放）。 */
public class EvaluationOutcome {

    private final double wFruit;
    private final double singleFruitWeightG;
    private final double fruitSetRate;
    private final double yieldKg;
    private final double marketableYieldKg;
    private final double waterUsedM3;
    private final double energyKWh;
    private final double co2UsedKg;
    private final double fertilizerUsedKg;
    private final double costYuan;
    private final double revenueYuan;
    private final double profitYuan;
    private final long highTemperatureMinutes;
    private final long highHumidityMinutes;
    private final long highVpdMinutes;
    private final double diseasePressureIntegral;
    private final int constraintViolations;
    private final double finalSeverityTotal;
    private final double meanTemperatureExceedanceC;
    private final double meanHumidityExceedancePct;
    private final List<Map<String, Object>> series;

    public EvaluationOutcome(double wFruit, double singleFruitWeightG, double fruitSetRate, double yieldKg,
                             double marketableYieldKg, double waterUsedM3, double energyKWh, double co2UsedKg,
                             double fertilizerUsedKg, double costYuan, double revenueYuan, double profitYuan,
                             long highTemperatureMinutes, long highHumidityMinutes, long highVpdMinutes,
                             double diseasePressureIntegral, int constraintViolations, double finalSeverityTotal,
                             double meanTemperatureExceedanceC, double meanHumidityExceedancePct,
                             List<Map<String, Object>> series) {
        this.meanTemperatureExceedanceC = meanTemperatureExceedanceC;
        this.meanHumidityExceedancePct = meanHumidityExceedancePct;
        this.wFruit = wFruit;
        this.singleFruitWeightG = singleFruitWeightG;
        this.fruitSetRate = fruitSetRate;
        this.yieldKg = yieldKg;
        this.marketableYieldKg = marketableYieldKg;
        this.waterUsedM3 = waterUsedM3;
        this.energyKWh = energyKWh;
        this.co2UsedKg = co2UsedKg;
        this.fertilizerUsedKg = fertilizerUsedKg;
        this.costYuan = costYuan;
        this.revenueYuan = revenueYuan;
        this.profitYuan = profitYuan;
        this.highTemperatureMinutes = highTemperatureMinutes;
        this.highHumidityMinutes = highHumidityMinutes;
        this.highVpdMinutes = highVpdMinutes;
        this.diseasePressureIntegral = diseasePressureIntegral;
        this.constraintViolations = constraintViolations;
        this.finalSeverityTotal = finalSeverityTotal;
        this.series = series == null ? new ArrayList<Map<String, Object>>() : series;
    }

    public double getWFruit() { return wFruit; }

    public double getSingleFruitWeightG() { return singleFruitWeightG; }

    public double getFruitSetRate() { return fruitSetRate; }

    public double getYieldKg() { return yieldKg; }

    public double getMarketableYieldKg() { return marketableYieldKg; }

    public double getWaterUsedM3() { return waterUsedM3; }

    public double getEnergyKWh() { return energyKWh; }

    public double getCo2UsedKg() { return co2UsedKg; }

    public double getFertilizerUsedKg() { return fertilizerUsedKg; }

    public double getCostYuan() { return costYuan; }

    public double getRevenueYuan() { return revenueYuan; }

    public double getProfitYuan() { return profitYuan; }

    public long getHighTemperatureMinutes() { return highTemperatureMinutes; }

    public long getHighHumidityMinutes() { return highHumidityMinutes; }

    public long getHighVpdMinutes() { return highVpdMinutes; }

    public double getDiseasePressureIntegral() { return diseasePressureIntegral; }

    public int getConstraintViolations() { return constraintViolations; }

    public double getFinalSeverityTotal() { return finalSeverityTotal; }

    /**
     * 逐步统计的平均超温量（℃）：只累加 `max(0, 温度 − 设定值)`，即**控制器能作用的方向**。
     *
     * <p>不能用逐日序列算这个指标：序列取的是每天最后一步（午夜）的采样，
     * 夜间低温会把"平均偏差"整体抬高到 6℃ 量级，掩盖掉真正由控制方式造成的差异。
     * 只取正向超出量后，数值才反映"该降的温有没有降下来"。</p>
     */
    public double getMeanTemperatureExceedanceC() { return meanTemperatureExceedanceC; }

    /** 逐步统计的平均超湿量（%RH）：只累加 `max(0, 湿度 − 设定值)`。 */
    public double getMeanHumidityExceedancePct() { return meanHumidityExceedancePct; }

    public List<Map<String, Object>> getSeries() { return Collections.unmodifiableList(series); }
}
