package com.example.Ece.agent.parameter;

/**
 * 一条参数出处登记，对应 {@code agent_parameter_source} 表的一行。
 *
 * <p>{@code status} 不落库（表是 create-only 建好的，没有状态列），由 {@code sourceUrl} 与前缀解析得出，
 * 解析规则集中在 {@link ParameterProvenance}。</p>
 */
public class ParameterSource {

    /**
     * 登记表主键。
     *
     * <p>由 {@link ParameterRegistry} 反射枚举构造的实例**没有**主键（尚未落库），取 0；
     * 从 {@code agent_parameter_source} 读回来的实例带真实主键。
     * 这个值不是装饰：引用合并的去重键是「来源表|来源ID|字段|片段号」，
     * 参数条目若都用同一个占位 ID，同一参数在不同步骤被引用时会被判成不同证据而重复编号。</p>
     */
    private final long id;
    private final String code;
    private final String name;
    private final String valueText;
    private final String unit;
    private final String sourceName;
    private final String sourceUrl;
    private final String version;

    public ParameterSource(String code, String name, String valueText, String unit,
                           String sourceName, String sourceUrl, String version) {
        this(0L, code, name, valueText, unit, sourceName, sourceUrl, version);
    }

    public ParameterSource(long id, String code, String name, String valueText, String unit,
                           String sourceName, String sourceUrl, String version) {
        this.id = id;
        this.code = code;
        this.name = name;
        this.valueText = valueText;
        this.unit = unit;
        this.sourceName = sourceName;
        this.sourceUrl = sourceUrl;
        this.version = version;
    }

    public long getId() {
        return id;
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
