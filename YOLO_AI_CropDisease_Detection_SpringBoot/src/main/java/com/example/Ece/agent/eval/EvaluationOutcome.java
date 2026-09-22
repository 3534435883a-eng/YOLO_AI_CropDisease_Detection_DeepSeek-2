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
    private final List<Map<String, Object>> series;

    public EvaluationOutcome(double wFruit, double singleFruitWeightG, double fruitSetRate, double yieldKg,
                             double marketableYieldKg, double waterUsedM3, double energyKWh, double co2UsedKg,
                             double fertilizerUsedKg, double costYuan, double revenueYuan, double profitYuan,
                             long highTemperatureMinutes, long highHumidityMinutes, long highVpdMinutes,
                             double diseasePressureIntegral, int constraintViolations, double finalSeverityTotal,
                             List<Map<String, Object>> series) {
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

    public List<Map<String, Object>> getSeries() { return Collections.unmodifiableList(series); }
}
