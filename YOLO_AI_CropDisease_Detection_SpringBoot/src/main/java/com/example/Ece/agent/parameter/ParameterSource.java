package com.example.Ece.agent.parameter;

/**
 * 一条参数出处登记，对应 {@code agent_parameter_source} 表的一行。
 *
 * <p>{@code status} 不落库（表是 create-only 建好的，没有状态列），由 {@code sourceUrl} 与前缀解析得出，
 * 解析规则集中在 {@link ParameterProvenance}。</p>
 */
public class ParameterSource {

    private final String code;
    private final String name;
    private final String valueText;
    private final String unit;
    private final String sourceName;
    private final String sourceUrl;
    private final String version;

    public ParameterSource(String code, String name, String valueText, String unit,
                           String sourceName, String sourceUrl, String version) {
        this.code = code;
        this.name = name;
        this.valueText = valueText;
        this.unit = unit;
        this.sourceName = sourceName;
        this.sourceUrl = sourceUrl;
        this.version = version;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getValueText() {
        return valueText;
    }

    public String getUnit() {
        return unit;
    }

    public String getSourceName() {
        return sourceName;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public String getVersion() {
        return version;
    }

    public ParameterProvenance.Status getStatus() {
        return ParameterProvenance.parse(sourceName, sourceUrl);
    }
}
