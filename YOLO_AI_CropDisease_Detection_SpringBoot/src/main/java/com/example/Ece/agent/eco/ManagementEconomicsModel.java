package com.example.Ece.agent.eco;

import com.example.Ece.agent.crop.TomatoCropState;
import org.springframework.stereotype.Component;

/**
 * 管理与经济模型（模型五）。
 *
 * <p>把资源消耗、作物产量与病害损失折算为成本、产值与利润，使"AI 决策"具备经济含义：
 * 成本按累计投入逐日累加；产值由**累计**果实干重折算的鲜重产量乘以分级价格；
 * 病害损失通过 {@link DiseaseState#getDiseaseDamageFactor()} 折减商品产量。</p>
 *
 * <p><b>纯函数约定</b>：不保存可变实例状态，输出只是入参的确定函数。所有价格为示例参数
 * （见 {@link EconomicsParameters}），正式材料需替换为当地实际价格并标注来源。</p>
 */
@Component
public class ManagementEconomicsModel {

    private static final double MINUTES_PER_DAY = 1440.0;
    private static final double GRAMS_PER_KILOGRAM = 1000.0;

    /** 初始经济状态：零投入、零产出。 */
    public EconomicsState initial() {
        return new EconomicsState(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
    }

    /**
     * 推进一步（{@code minutes} 分钟）。
     *
     * @param current 当前经济状态；为 {@code null} 时按 {@link #initial()} 处理
     * @param usage   本步资源消耗；为 {@code null} 时视为无消耗
     * @param crop    当前作物状态（取果实干重折算产量）；为 {@code null} 时产量按 0 处理
     * @param disease 当前病虫害状态（取产量损失因子）；为 {@code null} 时无损失
     * @param minutes 本次推进的分钟数（负数按 0 处理）
     * @return 推进后的经济状态
     */
    public EconomicsState advance(EconomicsState current, ResourceUsage usage, TomatoCropState crop,
                                  DiseaseState disease, int minutes) {
        EconomicsState base = current == null ? initial() : current;
        ResourceUsage used = usage == null ? ResourceUsage.none() : usage;
        double dayFraction = Math.max(0, minutes) / MINUTES_PER_DAY;

        double waterUsedM3 = base.getWaterUsedM3() + used.getWaterM3();
        double energyKWh = base.getEnergyKWh() + used.getEnergyKWh();
        double co2UsedKg = base.getCo2UsedKg() + used.getCo2Kg();
        double fertilizerUsedKg = base.getFertilizerUsedKg() + used.getFertilizerKg();
        double pesticideUsedKg = base.getPesticideUsedKg() + used.getPesticideKg();
        double laborHours = base.getLaborHours() + used.getLaborHours();

        double damageFactor = disease == null ? 1.0 : clamp(disease.getDiseaseDamageFactor(), 0.0, 1.0);
        double fruitDryGramPerM2 = crop == null ? 0.0 : Math.max(0.0, crop.getWFruit());
        double yieldKg = fruitDryGramPerM2 * EconomicsParameters.GREENHOUSE_AREA_M2 / GRAMS_PER_KILOGRAM
                / EconomicsParameters.FRUIT_DRY_MATTER_FRACTION;
        double marketableYieldKg = yieldKg * damageFactor;
        double gradeAKg = marketableYieldKg * EconomicsParameters.GRADE_A_RATIO;
        double gradeBKg = marketableYieldKg - gradeAKg;

        double revenueYuan = gradeAKg * EconomicsParameters.GRADE_A_PRICE_YUAN_PER_KG
                + gradeBKg * EconomicsParameters.GRADE_B_PRICE_YUAN_PER_KG;

        // 注意：成本必须按【本步用量】计价。早期版本误用累计用量乘单价，导致每步都在为"历史总量"重复计费，
        // 成本呈二次增长（120 天后虚高到千万元级）。累计量只用于展示与单位产量指标。
        double stepCost = used.getWaterM3() * EconomicsParameters.WATER_YUAN_PER_M3
                + used.getEnergyKWh() * EconomicsParameters.ENERGY_YUAN_PER_KWH
                + used.getCo2Kg() * EconomicsParameters.CO2_YUAN_PER_KG
                + used.getFertilizerKg() * EconomicsParameters.FERTILIZER_YUAN_PER_KG
                + used.getPesticideKg() * EconomicsParameters.PESTICIDE_YUAN_PER_KG
                + used.getLaborHours() * EconomicsParameters.LABOR_YUAN_PER_HOUR;
        double costYuan = base.getCostYuan() + stepCost
                + EconomicsParameters.FIXED_COST_YUAN_PER_DAY * dayFraction;
        double profitYuan = revenueYuan - costYuan;

        double waterPerYield = yieldKg > 0.0 ? waterUsedM3 / yieldKg : 0.0;
        double energyPerYield = yieldKg > 0.0 ? energyKWh / yieldKg : 0.0;

        return new EconomicsState(waterUsedM3, energyKWh, co2UsedKg, fertilizerUsedKg, pesticideUsedKg,
                laborHours, yieldKg, marketableYieldKg, costYuan, revenueYuan, profitYuan,
                waterPerYield, energyPerYield);
    }

    private double clamp(double value, double lower, double upper) {
        return Math.max(lower, Math.min(upper, value));
    }
}
