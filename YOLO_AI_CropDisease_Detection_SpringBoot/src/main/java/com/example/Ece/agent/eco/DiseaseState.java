package com.example.Ece.agent.eco;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * 病虫害流行状态（不可变值对象，模型 2）。
 *
 * <p>四种病害的严重度、病原基数与潜伏进度以 {@link EnumMap} 保存，
 * 构造时做防御性拷贝并包装为不可修改视图，因此对象一经构造不再改变，
 * {@link PestDiseaseEpidemicModel#advance} 是输入的纯函数，便于回放与确定性验证。</p>
 *
 * <p>所有字段均为模拟量，<b>不代表任何实测数据</b>。</p>
 */
public class DiseaseState {

    private final Map<DiseaseKind, Double> severity;
    private final Map<DiseaseKind, Double> inoculum;
    private final Map<DiseaseKind, Double> latent;
    private final int infectionEvents;
    private final double diseaseDamageFactor;
    private final double pestPopulation;

    /**
     * 全参构造函数。
     *
     * <p>三个 Map 均按 {@link DiseaseKind} 全枚举做防御性拷贝：缺失的键按 0.0 处理，
     * 传入 {@code null} 时按全 0 处理，拷贝后包装为不可修改视图。</p>
     *
     * @param severity            各病害严重度（%）
     * @param inoculum            各病害病原基数（0~1）
     * @param latent              各病害潜伏进度（0~1）
     * @param infectionEvents     累计侵染事件次数
     * @param diseaseDamageFactor 病害产量损失因子（0~1，1 表示无损失）
     * @param pestPopulation      害虫种群数量
     */
    public DiseaseState(Map<DiseaseKind, Double> severity, Map<DiseaseKind, Double> inoculum,
                        Map<DiseaseKind, Double> latent, int infectionEvents,
                        double diseaseDamageFactor, double pestPopulation) {
        this.severity = copyOf(severity);
        this.inoculum = copyOf(inoculum);
        this.latent = copyOf(latent);
        this.infectionEvents = infectionEvents;
        this.diseaseDamageFactor = diseaseDamageFactor;
        this.pestPopulation = pestPopulation;
    }

    /** 指定病害的严重度（%）。{@code kind} 为 {@code null} 时返回 0.0。 */
    public double severity(DiseaseKind kind) {
        return valueOf(severity, kind);
    }

    /** 指定病害的病原基数（0~1）。{@code kind} 为 {@code null} 时返回 0.0。 */
    public double inoculum(DiseaseKind kind) {
        return valueOf(inoculum, kind);
    }

    /** 指定病害的潜伏进度（0~1）。{@code kind} 为 {@code null} 时返回 0.0。 */
    public double latent(DiseaseKind kind) {
        return valueOf(latent, kind);
    }

    /** 累计侵染事件次数（四种病害合计）。 */
    public int getInfectionEvents() {
        return infectionEvents;
    }

    /** 病害产量损失因子（0~1，1 表示无损失）。 */
    public double getDiseaseDamageFactor() {
        return diseaseDamageFactor;
    }

    /** 害虫种群数量。 */
    public double getPestPopulation() {
        return pestPopulation;
    }

    /** 四种病害严重度之和（%）。 */
    public double totalSeverity() {
        double total = 0.0;
        for (DiseaseKind kind : DiseaseKind.values()) {
            total += severity(kind);
        }
        return total;
    }

    /** 全枚举防御性拷贝。 */
    private static Map<DiseaseKind, Double> copyOf(Map<DiseaseKind, Double> source) {
        Map<DiseaseKind, Double> copy = new EnumMap<DiseaseKind, Double>(DiseaseKind.class);
        for (DiseaseKind kind : DiseaseKind.values()) {
            Double value = source == null ? null : source.get(kind);
            copy.put(kind, value == null ? 0.0 : value.doubleValue());
        }
        return Collections.unmodifiableMap(copy);
    }

    /** 按键取值，缺失或 {@code null} 键返回 0.0。 */
    private static double valueOf(Map<DiseaseKind, Double> source, DiseaseKind kind) {
        if (kind == null) {
            return 0.0;
        }
        Double value = source.get(kind);
        return value == null ? 0.0 : value.doubleValue();
    }
}
