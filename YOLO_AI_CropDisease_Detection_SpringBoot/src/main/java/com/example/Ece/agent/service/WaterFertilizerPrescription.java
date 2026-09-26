package com.example.Ece.agent.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 水肥调控处方（待人工确认的草案）。
 *
 * <p><b>为什么单独建一个值对象，而不是塞进 {@link Prescription}</b>：{@code Prescription} 的
 * {@code actions} 是 {@code List<String>}——"开启灌溉（规则 IRRIGATE_DRY），预计消耗 0.06 m³"这类
 * 人读句子。句子能被模型复述，但**不能保证数字不被复述错**，也无法逐条标注出处。
 * 水肥处方是要被农户照着执行的，每条必须有可核对的量、单位与出处，因此这里用结构化条目。</p>
 *
 * <p><b>与设备动作的区别（必须讲清）</b>：{@code Prescription} 描述的是"规则层现在会开关哪些设备"，
 * 是**动作**；本对象描述的是"浇多少、追多少"，是**量**。二者不是同一件事，不得互相替代：
 * 灌溉命令为 ON 不等于本次一定达到处方水量，反之处方给出水量也不代表系统会执行。</p>
 *
 * <p><b>未建模的部分必须显式列出</b>（见 {@link #getUnmodelled()}）：宁可让使用者看到
 * "生育期未接入运行状态，因此无法判断现在该不该追肥"，也不要给一个看起来完整的假处方。</p>
 */
public class WaterFertilizerPrescription {

    /** 一条处方条目。数值 + 单位 + 出处，缺一不可。 */
    public static final class Item {

        private final String key;
        private final String label;
        private final String value;
        private final String unit;
        private final String parameterCode;
        private final String sourceName;
        private final String sourceUrl;
        private final long sourceId;

        public Item(String key, String label, String value, String unit,
                    String parameterCode, String sourceName, String sourceUrl) {
            this(key, label, value, unit, parameterCode, sourceName, sourceUrl, 0L);
        }

        public Item(String key, String label, String value, String unit,
                    String parameterCode, String sourceName, String sourceUrl, long sourceId) {
            this.key = key;
            this.label = label;
            this.value = value;
            this.unit = unit;
            this.parameterCode = parameterCode;
            this.sourceName = sourceName;
            this.sourceUrl = sourceUrl;
            this.sourceId = sourceId;
        }

        /** 登记表主键。引用合并按「来源表|来源ID|字段|片段号」去重，故同一参数必须给出稳定 ID。 */
        public long getSourceId() { return sourceId; }

        public String getKey() { return key; }

        public String getLabel() { return label; }

        public String getValue() { return value; }

        public String getUnit() { return unit; }

        public String getParameterCode() { return parameterCode; }

        public String getSourceName() { return sourceName; }

        public String getSourceUrl() { return sourceUrl; }

        /**
         * 是否可对外引用。
         *
         * <p>判据是**有可解析的出处链接**，与 {@code ParameterProvenance.Status.VERIFIED} 同口径。
         * 没有链接的值仍然会出现在处方里（否则等于隐瞒模型确实用了这个数），
         * 但**不得进入引用编号**——引用编号的含义是"这条能被核对到出处"，
         * 给未登记来源的数值编号会把这条纪律稀释成形式。</p>
         */
        public boolean isCitable() {
            return sourceUrl != null && !sourceUrl.trim().isEmpty();
        }

        /** 供模型阅读的一行。数字与单位紧邻，减少复述时的错位。 */
        public String describe() {
            StringBuilder builder = new StringBuilder();
            builder.append(label).append(' ').append(value);
            if (unit != null && !unit.trim().isEmpty()) {
                builder.append(' ').append(unit);
            }
            builder.append(citableSuffix());
            return builder.toString();
        }

        private String citableSuffix() {
            if (isCitable()) {
                return "（出处：" + sourceName + "）";
            }
            return "（未登记出处，不得作为结论依据）";
        }
    }

    private final boolean irrigationDue;
    private final double soilMoisturePct;
    private final double triggerPct;
    private final String summary;
    private final List<Item> items;
    private final List<String> cautions;
    private final List<String> unmodelled;

    public WaterFertilizerPrescription(boolean irrigationDue, double soilMoisturePct, double triggerPct,
                                       String summary, List<Item> items, List<String> cautions,
                                       List<String> unmodelled) {
        this.irrigationDue = irrigationDue;
        this.soilMoisturePct = soilMoisturePct;
        this.triggerPct = triggerPct;
        this.summary = summary;
        this.items = items == null ? new ArrayList<Item>() : new ArrayList<Item>(items);
        this.cautions = cautions == null ? new ArrayList<String>() : new ArrayList<String>(cautions);
        this.unmodelled = unmodelled == null ? new ArrayList<String>() : new ArrayList<String>(unmodelled);
    }

    /** 按当前土壤水分是否已达到灌溉下限。 */
    public boolean isIrrigationDue() { return irrigationDue; }

    public double getSoilMoisturePct() { return soilMoisturePct; }

    public double getTriggerPct() { return triggerPct; }

    public String getSummary() { return summary; }

    public List<Item> getItems() { return Collections.unmodifiableList(items); }

    public List<String> getCautions() { return Collections.unmodifiableList(cautions); }

    /** 本处方**没有**覆盖到的方面，逐条写明原因。 */
    public List<String> getUnmodelled() { return Collections.unmodifiableList(unmodelled); }

    /** 只取有出处的条目，供证据/引用通道使用。 */
    public List<Item> citableItems() {
        List<Item> result = new ArrayList<Item>();
        for (Item item : items) {
            if (item.isCitable()) {
                result.add(item);
            }
        }
        return result;
    }

    /** 紧凑单行摘要，供工具 note 通道使用（note 是编排层唯一完整传给模型的工具输出）。 */
    public String compact() {
        StringBuilder builder = new StringBuilder();
        for (Item item : items) {
            if (builder.length() > 0) {
                builder.append("；");
            }
            builder.append(item.describe());
        }
        return builder.toString();
    }
}
