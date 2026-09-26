package com.example.Ece.agent.service;

import com.example.Ece.agent.crop.CropStage;
import com.example.Ece.agent.eco.SoilParameters;
import com.example.Ece.agent.engine.TomatoSimulationEngine;
import com.example.Ece.agent.model.SimulationState;
import com.example.Ece.agent.parameter.ParameterSource;
import com.example.Ece.agent.parameter.ParameterSourceService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 水肥调控处方服务：把"浇多少、追多少"从散落的常量变成一份**带出处**的处方。
 *
 * <p><b>为什么要做这一步</b>：2026-09-26 端到端实测中，智能体被问到
 * "番茄第一穗果膨大期该怎么浇水、追什么肥、各用多少量"，回答是"依据不足，我无法给出具体用量"。
 * 但平台**其实是有这些数的**——论文取值、规则层定额、引擎常量都在。问题是它们只活在
 * {@code eco/} 与 {@code eval/} 两个包里：离线评测跑得到，**智能体够不着**。
 * 于是出现"模型算得出、智能体答不上"的割裂。本服务把这条链补上。</p>
 *
 * <p><b>本处方的信息来源分三类，必须分别标注，不得混同</b>：</p>
 * <ol>
 *   <li><b>有出处</b>（论文 + DOI）：灌溉下限比例、全季追肥量、追肥次数。
 *       这些进入引用编号，可对外陈述。</li>
 *   <li><b>有推导</b>：由上一条算术得到（单次追肥量、折算毫米数）。
 *       输入可引用时一并标为可引用，并在出处里写明推导关系。</li>
 *   <li><b>无出处</b>：田间持水量、单次滴灌量等，登记表里就是"未核实/示例值"。
 *       **照样出现在处方里**——不写等于隐瞒模型用了这个数——但明确标注不得作为结论依据，
 *       且**不进入引用编号**。</li>
 * </ol>
 *
 * <p><b>本处方明确不覆盖什么</b>：见 {@link WaterFertilizerPrescription#getUnmodelled()}。
 * 最要紧的一条是：运行状态里**没有作物生育期**，因此无法判断"现在是否到了该追肥的节点"。
 * 这类"看起来能算其实不能算"的地方，一律如实列为未建模，不给假精度。</p>
 */
@Service
public class WaterFertilizerPrescriptionService {

    /** 灌溉触发下限：田间持水量 × 该比例。来自论文，可引用。 */
    public static final String CODE_TRIGGER_FRACTION = "SOIL_IRRIGATION_TRIGGER_FRACTION_OF_FC";
    /** 田间持水量本身。登记表里未核实。 */
    public static final String CODE_FIELD_CAPACITY = "SOIL_FIELD_CAPACITY_PCT";
    /** 全季追肥总量。来自论文，可引用。 */
    public static final String CODE_TOPDRESSING_TOTAL = "SOIL_SEASON_TOPDRESSING_KG_PER_HA";
    /** 追肥次数。来自论文，可引用。 */
    public static final String CODE_TOPDRESSING_TIMES = "SOIL_TOPDRESSING_APPLICATIONS";
    /** 单步滴灌水量。登记表里为"示例值：演示参数"，不可引用。 */
    public static final String CODE_IRRIGATION_LITERS = "ENG_IRRIGATION_L_PER_TICK";

    private final ParameterSourceService parameterSourceService;

    public WaterFertilizerPrescriptionService(ParameterSourceService parameterSourceService) {
        this.parameterSourceService = parameterSourceService;
    }

    /**
     * 依据当前状态与参数登记，拟一份水肥处方。
     *
     * <p>{@code state} 为 null 时（尚未创建运行）仍返回完整处方，只是不给"当前是否触发灌溉"的判断——
     * 制度部分与运行状态无关，不该因为没起运行就整份作废。</p>
     */
    public WaterFertilizerPrescription draft(SimulationState state) {
        Map<String, ParameterSource> registry = registryByCode();

        double fieldCapacity = SoilParameters.FIELD_CAPACITY_PCT;
        double triggerFraction = SoilParameters.IRRIGATION_TRIGGER_FRACTION_OF_FC;
        double triggerPct = fieldCapacity * triggerFraction;
        double irrigationLiters = TomatoSimulationEngine.IRRIGATION_L_PER_TICK;
        double millimeters = irrigationLiters / SoilParameters.BED_AREA_M2;

        List<WaterFertilizerPrescription.Item> items = new ArrayList<WaterFertilizerPrescription.Item>();

        // ---- 灌溉 ----
        items.add(item("IRRIGATION_TRIGGER_FRACTION", "灌溉下限（占田间持水量）",
                format(triggerFraction), "比例", registry, CODE_TRIGGER_FRACTION));
        items.add(item("FIELD_CAPACITY", "田间持水量",
                format(fieldCapacity), "%vol", registry, CODE_FIELD_CAPACITY));
        items.add(derived("IRRIGATION_TRIGGER_PCT", "由此得到的土壤水分灌溉触发点",
                format(triggerPct), "%vol",
                "由「灌溉下限比例 × 田间持水量」推得；其中田间持水量未登记出处，故本值亦不得单独作为依据",
                registry, CODE_TRIGGER_FRACTION));
        items.add(item("IRRIGATION_LITERS", "单次滴灌水量",
                format(irrigationLiters), "L/次", registry, CODE_IRRIGATION_LITERS));
        items.add(derived("IRRIGATION_MM", "单次滴灌折合水深",
                format(millimeters), "mm/次",
                "由「单次水量 ÷ 种植床面积 " + format(SoilParameters.BED_AREA_M2) + " m²」推得",
                null, null));

        // ---- 追肥 ----
        items.add(item("TOPDRESSING_TOTAL", "全季追肥总量",
                format(SoilParameters.SEASON_TOPDRESSING_KG_PER_HA), "kg/hm²", registry, CODE_TOPDRESSING_TOTAL));
        items.add(item("TOPDRESSING_TIMES", "追肥次数",
                String.valueOf(SoilParameters.TOPDRESSING_APPLICATIONS), "次",
                registry, CODE_TOPDRESSING_TIMES));
        items.add(derived("TOPDRESSING_PER_TIME", "单次追肥量",
                format(SoilParameters.SEASON_TOPDRESSING_KG_PER_HA / SoilParameters.TOPDRESSING_APPLICATIONS),
                "kg/hm²",
                "由「全季总量 ÷ 次数」推得，两个输入值出自同一出处",
                registry, CODE_TOPDRESSING_TOTAL));
        items.add(derived("TOPDRESSING_NODES", "追肥施用节点",
                nodeText(), "",
                "论文的施用节点是「第 1 果直径 1.5~2.5 cm」「第 2 果直径 2~3 cm」；"
                        + "模型用生育阶段 " + stageText() + " 作可比拟的最接近代理，属本项目的建模选择、非论文原文",
                registry, CODE_TOPDRESSING_TOTAL));

        boolean irrigationDue = state != null && state.getSoilMoisturePct() <= triggerPct;
        String summary = buildSummary(state, triggerPct, irrigationDue, irrigationLiters);
        return new WaterFertilizerPrescription(irrigationDue,
                state == null ? Double.NaN : state.getSoilMoisturePct(), triggerPct,
                summary, items, cautions(state, irrigationDue), unmodelled());
    }

    // ------------------------------------------------------------------
    // 组装
    // ------------------------------------------------------------------

    private Map<String, ParameterSource> registryByCode() {
        Map<String, ParameterSource> map = new HashMap<String, ParameterSource>();
        for (ParameterSource source : parameterSourceService.list(null)) {
            map.put(source.getCode(), source);
        }
        return map;
    }

    /** 取登记表里的值语义：有出处就带出处，没出处照样给值但标为不可引用。 */
    private WaterFertilizerPrescription.Item item(String key, String label, String value, String unit,
                                                  Map<String, ParameterSource> registry, String code) {
        ParameterSource source = registry == null ? null : registry.get(code);
        if (source == null) {
            // 登记表里没有这条参数（例如尚未 refresh）。宁可标成"来源未登记"，
            // 也不要回落到代码注释——那会让"可核对"变成一句空话。
            return new WaterFertilizerPrescription.Item(key, label, value, unit, code,
                    "参数登记表中无此条目（可能尚未刷新登记）", null);
        }
        return new WaterFertilizerPrescription.Item(key, label, value, unit, code,
                source.getSourceName(), source.getSourceUrl(), source.getId());
    }

    /** 由其它条目推导出来的条目：出处指向输入值的出处，并在出处文字里写明推导关系。 */
    private WaterFertilizerPrescription.Item derived(String key, String label, String value, String unit,
                                                     String derivation,
                                                     Map<String, ParameterSource> registry, String code) {
        ParameterSource source = code == null || registry == null ? null : registry.get(code);
        if (source == null || source.getSourceUrl() == null || source.getSourceUrl().trim().isEmpty()) {
            return new WaterFertilizerPrescription.Item(key, label, value, unit, code, derivation, null);
        }
        return new WaterFertilizerPrescription.Item(key, label, value, unit, code,
                derivation + "（原始出处：" + source.getSourceName() + "）", source.getSourceUrl(),
                source.getId());
    }

    private String buildSummary(SimulationState state, double triggerPct, boolean due, double liters) {
        if (state == null) {
            return "当前没有进行中的模拟运行，无法判断是否触发灌溉；以下为制度层面的处方。";
        }
        String head = String.format(Locale.ROOT, "当前土壤水分 %.2f %%vol，灌溉触发点 %.2f %%vol：",
                state.getSoilMoisturePct(), triggerPct);
        if (due) {
            return head + String.format(Locale.ROOT, "**已达到灌溉下限**，规则层会建议滴灌一次约 %.0f L（待人工确认）。", liters);
        }
        return head + String.format(Locale.ROOT,
                "尚未达到灌溉下限，按此制度暂不需要灌溉（距触发点还差 %.2f 个百分点）。",
                state.getSoilMoisturePct() - triggerPct);
    }

    private List<String> cautions(SimulationState state, boolean due) {
        List<String> cautions = new ArrayList<String>();
        cautions.add("本处方是可执行的**量**，不是设备指令：系统不会因为本处方而开阀或施肥，"
                + "任何水肥操作须由人工确认后执行。");
        if (due) {
            cautions.add("触发灌溉不等于应立即灌溉。应先核对田间持水量是否为当地实测值——"
                    + "本模型的田间持水量未登记出处，若与实际土壤差异较大，触发点会整体偏移。");
        }
        cautions.add("追肥部分给出的是**制度**（总量与次数），不是「现在就该施」的判断，"
                + "原因见未建模说明第一条。");
        cautions.add("肥料品种与 N-P-K 配比、灌溉液 EC/pH 目标区间：当前知识库与参数登记均无依据，"
                + "本处方**不提供**，需按当地水肥一体化技术规程执行。");
        return cautions;
    }

    private List<String> unmodelled() {
        List<String> notes = new ArrayList<String>();
        notes.add("**生育期未接入运行状态**：模拟运行只跟踪环境与土壤，作物生育阶段只在离线评测层计算，"
                + "因此无法判断当前是否已到追肥节点，也无法按生育期给出差异化水肥量。追肥只能给制度。");
        notes.add("**氮收支尚未配平**：土壤速效氮在季内见底、养分因子长期钳在下限，"
                + "因此本处方的追肥量是**论文取值**，不是模型算出的最优值；改变施用量不会在模型中产生相应的产量响应。");
        notes.add("**湿帘用水未计入**：评测侧未折算湿帘蒸发耗水，水耗数字（若引用）偏低。");
        return notes;
    }

    private String nodeText() {
        return stageText();
    }

    private String stageText() {
        return stageLabel(CropStage.FRUIT_SET) + "、" + stageLabel(CropStage.FRUIT_GROWTH);
    }

    private String stageLabel(CropStage stage) {
        if (stage == CropStage.FRUIT_SET) {
            return "坐果期";
        }
        if (stage == CropStage.FRUIT_GROWTH) {
            return "果实膨大期";
        }
        return stage.name();
    }

    /** 统一小数位：避免 171.0 与 85.5 这类末尾零在模型复述时被读成不同精度。 */
    private String format(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "-";
        }
        return String.format(Locale.ROOT, "%.2f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}
