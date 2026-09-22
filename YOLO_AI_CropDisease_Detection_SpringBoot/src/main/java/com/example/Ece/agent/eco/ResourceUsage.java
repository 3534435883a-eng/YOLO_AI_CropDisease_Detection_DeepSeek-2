package com.example.Ece.agent.eco;

/** 单步资源消耗量（由设备动作与农事操作产生）。 */
public class ResourceUsage {

    private final double waterM3;
    private final double energyKWh;
    private final double co2Kg;
    private final double fertilizerKg;
    private final double pesticideKg;
    private final double laborHours;

    public ResourceUsage(double waterM3, double energyKWh, double co2Kg,
                         double fertilizerKg, double pesticideKg, double laborHours) {
        this.waterM3 = Math.max(0.0, waterM3);
        this.energyKWh = Math.max(0.0, energyKWh);
        this.co2Kg = Math.max(0.0, co2Kg);
        this.fertilizerKg = Math.max(0.0, fertilizerKg);
        this.pesticideKg = Math.max(0.0, pesticideKg);
        this.laborHours = Math.max(0.0, laborHours);
    }

    public static ResourceUsage none() {
        return new ResourceUsage(0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
    }

    public double getWaterM3() { return waterM3; }

    public double getEnergyKWh() { return energyKWh; }

    public double getCo2Kg() { return co2Kg; }

    public double getFertilizerKg() { return fertilizerKg; }

    public double getPesticideKg() { return pesticideKg; }

    public double getLaborHours() { return laborHours; }
}
