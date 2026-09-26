package com.example.Ece.agent.agri;

/**
 * 一次当地农情环境观测。
 *
 * <p><b>{@code source} 是这份数据最重要的字段。</b>项目全程不接真实传感器，
 * 因此"当地实时数据"只有两种可能：真的从数据服务取到了（{@link Source#OBSERVED}），
 * 或者用的是演示用模拟地（{@link Source#SIMULATED_LOCATION}）。
 * 两者混同就是把模拟冒充实测——这正是本项目最不能犯的错，所以来源由服务固定填入、
 * 不交给调用方判断，面板与报告也都必须显示它。</p>
 */
public class AgriEnvironmentObservation {

    /** 数据来源。 */
    public enum Source {
        /** 实时从数据服务取到。 */
        OBSERVED("实时观测"),
        /** 演示用模拟地档案，非实测。 */
        SIMULATED_LOCATION("演示用模拟地（非实测）");

        private final String label;

        Source(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    private final String location;
    private final double temperatureC;
    private final double humidityPct;
    private final String condition;
    private final String wind;
    private final String observedAt;
    private final Source source;
    private final String sourceName;
    private final String note;

    public AgriEnvironmentObservation(String location, double temperatureC, double humidityPct,
                                      String condition, String wind, String observedAt,
                                      Source source, String sourceName, String note) {
        this.location = location;
        this.temperatureC = temperatureC;
        this.humidityPct = humidityPct;
        this.condition = condition;
        this.wind = wind;
        this.observedAt = observedAt;
        this.source = source;
        this.sourceName = sourceName;
        this.note = note;
    }

    public String getLocation() {
        return location;
    }

    public double getTemperatureC() {
        return temperatureC;
    }

    public double getHumidityPct() {
        return humidityPct;
    }

    public String getCondition() {
        return condition;
    }

    public String getWind() {
        return wind;
    }

    public String getObservedAt() {
        return observedAt;
    }

    public Source getSource() {
        return source;
    }

    public String getSourceName() {
        return sourceName;
    }

    /** 说明为什么是当前来源（未配置 / 演示模式 / 调用失败），供界面如实展示。 */
    public String getNote() {
        return note;
    }
}
