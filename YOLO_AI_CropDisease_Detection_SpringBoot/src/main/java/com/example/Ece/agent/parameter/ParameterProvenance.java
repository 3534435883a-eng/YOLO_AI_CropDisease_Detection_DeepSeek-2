package com.example.Ece.agent.parameter;

/**
 * 参数出处的三种性质。
 *
 * <p><b>为什么要分类，而不是简单的"有/无出处"</b>：本项目的仿真参数缺出处有两种完全不同的情形，
 * 补救方式也不同——把两者混为一谈会让"待办清单"无法执行：</p>
 * <ul>
 *   <li>{@link #VERIFIED}：有明确可引用的出处（含 URL）。可直接对外引用。</li>
 *   <li>{@link #UNVERIFIED_LITERATURE}：值取自"典型文献区间"，但**指不出具体文献**
 *       （作者的动机是避免引用不确切的出处——动机正确，后果是参数不可追溯）。
 *       补救方式是**核对出处**。</li>
 *   <li>{@link #PLACEHOLDER}：连数值种类都是占位（当地水价/电价、设备铭牌功率）。
 *       补救方式不是查文献，而是**换成当地实际数据并标注日期**。</li>
 * </ul>
 *
 * <p><b>状态如何存储</b>：{@code agent_parameter_source} 表是 create-only 迁移建的，没有状态列，
 * 而本项目的约定是不修改既有表。因此状态用 {@code source_name} 的**前缀**表达
 * （见下方常量），由本类统一负责写入与解析——约定只在一处定义，避免各处各写一套。
 * 表里没有 {@code source_url} 即视为无出处，这是最硬的判据，前缀只是给人看的标签。</p>
 */
public final class ParameterProvenance {

    /** 未核实前缀。 */
    public static final String UNVERIFIED_PREFIX = "未核实：";

    /** 示例值前缀。 */
    public static final String PLACEHOLDER_PREFIX = "示例值：";

    private ParameterProvenance() {
    }

    /** 按前缀解析状态。有 URL 的一律视为已核实——URL 是最硬的判据。 */
    public static Status parse(String sourceName, String sourceUrl) {
        if (sourceUrl != null && !sourceUrl.trim().isEmpty()) {
            return Status.VERIFIED;
        }
        String name = sourceName == null ? "" : sourceName;
        if (name.startsWith(PLACEHOLDER_PREFIX)) {
            return Status.PLACEHOLDER;
        }
        if (name.startsWith(UNVERIFIED_PREFIX)) {
            return Status.UNVERIFIED_LITERATURE;
        }
        // 有出处名但没有 URL、也没带前缀：保守判为未核实，宁可低估可引用性。
        return Status.UNVERIFIED_LITERATURE;
    }

    /** 加前缀，供写入时使用。 */
    public static String decorate(Status status, String text) {
        if (status == Status.PLACEHOLDER) {
            return PLACEHOLDER_PREFIX + text;
        }
        if (status == Status.UNVERIFIED_LITERATURE) {
            return UNVERIFIED_PREFIX + text;
        }
        return text;
    }

    /** 参数出处性质。 */
    public enum Status {
        /** 有可引用的出处（带 URL）。 */
        VERIFIED("已核实"),
        /** 文献区间取值，待核对出处。 */
        UNVERIFIED_LITERATURE("未核实"),
        /** 示例值，需替换为当地实际数据。 */
        PLACEHOLDER("示例值");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }
}
