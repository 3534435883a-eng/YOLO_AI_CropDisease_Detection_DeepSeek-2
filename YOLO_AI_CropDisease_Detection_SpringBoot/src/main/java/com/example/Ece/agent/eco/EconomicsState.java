package com.example.Ece.agent.eco;

/** 管理与经济状态：累计投入、产量、产值与利润。 */
public class EconomicsState {

    private final double waterUsedM3;
    private final double energyKWh;
    private final double co2UsedKg;
    private final double fertilizerUsedKg;
    private final double pesticideUsedKg;
    private final double laborHours;
    private final double yieldKg;
    private final double marketableYieldKg;
    private final double costYuan;
    private final double revenueYuan;
    private final double profitYuan;
    private final double waterPerYield;
    private final double energyPerYield;

    public EconomicsState(double waterUsedM3, double energyKWh, double co2UsedKg, double fertilizerUsedKg,
                          double pesticideUsedKg, double laborHours, double yieldKg, double marketableYieldKg,
                          double costYuan, double revenueYuan, double profitYuan,
                          double waterPerYield, double energyPerYield) {
        this.waterUsedM3 = waterUsedM3;
        this.energyKWh = energyKWh;
        this.co2UsedKg = co2UsedKg;
        this.fertilizerUsedKg = fertilizerUsedKg;
        this.pesticideUsedKg = pesticideUsedKg;
        this.laborHours = laborHours;
        this.yieldKg = yieldKg;
        this.marketableYieldKg = marketableYieldKg;
        this.costYuan = costYuan;
        this.revenueYuan = revenueYuan;
        this.profitYuan = profitYuan;
        this.waterPerYield = waterPerYield;
        this.energyPerYield = energyPerYield;
    }

    public double getWaterUsedM3() { return waterUsedM3; }

    public double getEnergyKWh() { return energyKWh; }

    public double getCo2UsedKg() { return co2UsedKg; }

    public double getFertilizerUsedKg() { return fertilizerUsedKg; }

    public double getPesticideUsedKg() { return pesticideUsedKg; }

    public double getLaborHours() { return laborHours; }

    public double getYieldKg() { return yieldKg; }

    public double getMarketableYieldKg() { return marketableYieldKg; }

    public double getCostYuan() { return costYuan; }

    public double getRevenueYuan() { return revenueYuan; }

    public double getProfitYuan() { return profitYuan; }

    public double getWaterPerYield() { return waterPerYield; }

    public double getEnergyPerYield() { return energyPerYield; }
}
