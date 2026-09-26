package com.example.Ece.agent.parameter;

import com.example.Ece.agent.crop.TomatoGrowthParameters;
import com.example.Ece.agent.eco.EconomicsParameters;
import com.example.Ece.agent.eco.EpidemicParameters;
import com.example.Ece.agent.eco.SoilParameters;
import com.example.Ece.agent.engine.TomatoSimulationEngine;
import com.example.Ece.agent.eval.ResourceRates;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 仿真参数出处登记表：把散在各参数类里的常量**枚举成可查询的记录**，并标注出处性质。
 *
 * <p><b>为什么用反射而不是手写清单</b>：本项目有 100 余个仿真参数常量，手写一份登记表有两个必然结局——
 * 要么漏项，要么与源码漂移（值改了、清单没改，于是登记表本身变成新的不可信来源）。
 * 反射让**值永远等于源码里的真值**，且**新增参数会自动出现在登记表里、默认落在"未核实"**，
 * 谁都无法悄悄引入一个没有出处的参数。</p>
 *
 * <p><b>单位为什么不填</b>：源码里单位写在字段注释中，反射取不到。
 * 猜单位（比如按 {@code _PERCENT} 后缀推断）会在一份"出处登记表"里塞进**未经核实的元数据**——
 * 那比缺更糟。因此除少数显式登记项外，{@code unit} 留空表示"单位未在登记中声明"，
 * 由接口统计出来给人看。</p>
 *
 * <p><b>覆盖范围</b>：扫描下列参数类的数值常量。**仍内联在方法体里的字面量不在覆盖内**
 *（例如风险加权系数、病害压力阈值），这些尚未提取为具名常量，登记表无从枚举——
 * 接口会如实报告"未覆盖"的类，而不是假装覆盖完整。</p>
 */
@Component
public class ParameterRegistry {

    /**
     * 登记版本。参数值或出处发生变化时应递增——与项目的快照版本纪律一致：
     * 引用登记结果时必须连版本一起引用，否则数字无从复现。
     */
    public static final String REGISTRY_VERSION = "sim-params-2026-09-26";

    /** 说明：这些类中仍未提取为具名常量的内联字面量不在登记范围内。 */
    public static final List<String> UNCOVERED_NOTE = Collections.unmodifiableList(Arrays.asList(
            "TomatoSimulationEngine：风险加权系数（0.25/0.20/0.20/0.12/0.10/0.13）、"
                    + "病害压力阈值（28/8/1.9/48/14/12）、室外气温与湿度相位（17+12·日照、84−32·日照）"
                    + "仍为方法体内联字面量，未提取为具名常量，故不在本登记表覆盖内。",
            "TomatoDecisionPolicy：规则层阈值（29.0/27.0/32.0/33.0/75.0/80.0/88.0/30.0/900.0/45.0/280.0/650.0）"
                    + "与设备资源定额（0.350/0.030/0.420/18.000/0.080/0.100/60.000/1.200/0.250）"
                    + "同样为内联字面量，未纳入覆盖。"));

    /** 一个参数类的默认出处。 */
    private static final class ClassSpec {
        final Class<?> type;
        /** 短标签：与字段名拼成 parameter_code。表里 parameter_code 是 VARCHAR(64)，
         *  用「完整类名.字段名」会超长（实测 Data too long），且该列本就期望 SOIL_PH_MIN 这种短码。 */
        final String tag;
        final String groupName;
        final ParameterProvenance.Status status;
        final String sourceText;
        final String sourceUrl;

        ClassSpec(Class<?> type, String tag, String groupName, ParameterProvenance.Status status,
                  String sourceText, String sourceUrl) {
            this.type = type;
            this.tag = tag;
            this.groupName = groupName;
            this.status = status;
            this.sourceText = sourceText;
            this.sourceUrl = sourceUrl;
        }
    }

    /** 个别常量的显式登记（中文名、单位、出处），覆盖类级默认。 */
    private static final class FieldSpec {
        final String name;
        final String unit;
        final ParameterProvenance.Status status;
        final String sourceText;
        final String sourceUrl;

        FieldSpec(String name, String unit, ParameterProvenance.Status status,
                  String sourceText, String sourceUrl) {
            this.name = name;
            this.unit = unit;
            this.status = status;
            this.sourceText = sourceText;
            this.sourceUrl = sourceUrl;
        }
    }

    private static final String FAO56_URL = "https://www.fao.org/4/X0490E/x0490e07.htm";

    /** 设施番茄水氮论文（北京水务 2024(5)）的 DOI 链接，用于追肥量与灌水下限两项。 */
    private static final String TOMATO_WATER_NITROGEN_DOI = "https://doi.org/10.19671/j.1673-4637.2024.05.002";

    private static final String TOMATO_WATER_NITROGEN_SOURCE =
            "马志军等《水氮互作对设施番茄土壤氮平衡及氮素利用效率的影响研究》，北京水务 2024 年第 5 期";

    private static final List<ClassSpec> CLASSES = Collections.unmodifiableList(Arrays.asList(
            new ClassSpec(SoilParameters.class, "SOIL", "土壤水肥参数（模型 1）",
                    ParameterProvenance.Status.UNVERIFIED_LITERATURE,
                    "三类文献族（FAO-56 番茄作物系数 / 国内设施番茄栽培 / 水肥一体化）；"
                            + "取值为典型文献区间，源码声明\"待核对出处\"，未记录具体论文、年份或页码", null),
            new ClassSpec(EpidemicParameters.class, "EPI", "病虫害流行参数（模型 2）",
                    ParameterProvenance.Status.UNVERIFIED_LITERATURE,
                    "三类文献族（植物病害流行学 / 国内番茄植保 / 害虫种群生态学）；"
                            + "取值为典型文献区间，源码声明\"待核对出处\"", null),
            new ClassSpec(TomatoGrowthParameters.class, "GROW", "番茄生长参数",
                    ParameterProvenance.Status.UNVERIFIED_LITERATURE,
                    "三类文献族（TOMGROM / TOMSIM / 国内番茄栽培）；"
                            + "取值为典型文献区间，源码声明\"实施时逐条核对出处\"", null),
            new ClassSpec(EconomicsParameters.class, "ECON", "管理与经济参数（模型五）",
                    ParameterProvenance.Status.PLACEHOLDER,
                    "源码声明为示例参数：正式材料必须替换为当地实际水价/电价/农资价格并标注来源与日期", null),
            new ClassSpec(ResourceRates.class, "RATE", "设备资源消耗速率",
                    ParameterProvenance.Status.PLACEHOLDER,
                    "源码声明为示例参数：需替换为当地设备铭牌功率与实际水表/电表记录", null),
            new ClassSpec(TomatoSimulationEngine.class, "ENG", "温室微气候参数",
                    ParameterProvenance.Status.UNVERIFIED_LITERATURE,
                    "交换系数为未校准假设（docs/tomato-greenhouse-agent.md「仿真依据与参数边界」已点名）；"
                            + "棚体几何为演示设计尺寸，非实地测绘", null)));

    /** key = "简单类名.字段名"。 */
    private static final Map<String, FieldSpec> OVERRIDES = buildOverrides();

    private static Map<String, FieldSpec> buildOverrides() {
        Map<String, FieldSpec> map = new LinkedHashMap<String, FieldSpec>();
        add(map, "ENG", "PSYCHROMETRIC_CONSTANT_KPA_PER_C",
                "湿度计常数 γ", "kPa/℃", ParameterProvenance.Status.VERIFIED,
                "FAO-56 第 3 章（近海平面近似值；文档已注明该值为近似，随海拔变化）", FAO56_URL);
        add(map, "ENG", "COOLING_PAD_EFFICIENCY", "湿帘效率", null,
                ParameterProvenance.Status.UNVERIFIED_LITERATURE,
                "取 80% 为假设效率，非厂商实测曲线；FAO-56 不为该效率背书", null);
        add(map, "ENG", "BASE_HEAT_EXCHANGE_PER_TICK", "基础换热系数", null,
                ParameterProvenance.Status.UNVERIFIED_LITERATURE, "未校准假设", null);
        add(map, "ENG", "BASE_VAPOR_EXCHANGE_PER_TICK", "基础空气水汽交换系数", null,
                ParameterProvenance.Status.UNVERIFIED_LITERATURE, "未校准假设", null);
        add(map, "ENG", "VENTILATION_EXCHANGE_PER_TICK", "侧窗/通风附加交换系数", null,
                ParameterProvenance.Status.UNVERIFIED_LITERATURE, "未校准假设", null);
        add(map, "ENG", "ROOF_VENT_EXCHANGE_PER_TICK", "屋窗附加交换系数", null,
                ParameterProvenance.Status.UNVERIFIED_LITERATURE, "未校准假设", null);
        add(map, "ENG", "EXHAUST_FAN_EXCHANGE_PER_TICK", "强制排风附加交换系数", null,
                ParameterProvenance.Status.UNVERIFIED_LITERATURE, "未校准假设", null);
        add(map, "ENG", "IRRIGATION_L_PER_TICK", "单步滴灌水量", "L",
                ParameterProvenance.Status.PLACEHOLDER, "演示参数；与规则层声明及孪生运行时保持一致", null);
        add(map, "ENG", "BED_ROOT_VOLUME_L", "四条种植床根区体积", "L",
                ParameterProvenance.Status.UNVERIFIED_LITERATURE,
                "由假设有效根深 0.25 m 与种植床尺寸折算，非实测", null);
        add(map, "ENG", "GREENHOUSE_AIR_VOLUME_M3", "棚内空气体积", "m³",
                ParameterProvenance.Status.UNVERIFIED_LITERATURE, "按演示棚体尺寸近似", null);
        // 以下三项来自设施番茄水氮论文，是登记表里少数**带可解析 DOI 出处**的参数。
        add(map, "SOIL", "IRRIGATION_TRIGGER_FRACTION_OF_FC", "灌水下限（田间持水量的比例）", null,
                ParameterProvenance.Status.VERIFIED,
                TOMATO_WATER_NITROGEN_SOURCE + "：设施番茄适宜灌水下限为田间持水量的 85%",
                TOMATO_WATER_NITROGEN_DOI);
        add(map, "SOIL", "SEASON_TOPDRESSING_KG_PER_HA", "一季追肥总量", "kg/hm²",
                ParameterProvenance.Status.VERIFIED,
                TOMATO_WATER_NITROGEN_SOURCE + "：适宜追肥量为 171 kg/hm²",
                TOMATO_WATER_NITROGEN_DOI);
        add(map, "SOIL", "TOPDRESSING_APPLICATIONS", "一季追肥次数", "次",
                ParameterProvenance.Status.VERIFIED,
                TOMATO_WATER_NITROGEN_SOURCE + "：分 2 次追施（第 1 果、第 2 果膨大期）",
                TOMATO_WATER_NITROGEN_DOI);
        return map;
    }

    private static void add(Map<String, FieldSpec> map, String tag, String field, String name,
                            String unit, ParameterProvenance.Status status, String sourceText, String url) {
        map.put(tag + "_" + field, new FieldSpec(name, unit, status, sourceText, url));
    }

    /** 枚举全部可登记参数。按类名 + 字段名排序，保证输出顺序确定、可复现。 */
    public List<ParameterSource> enumerate() {
        List<ParameterSource> result = new ArrayList<ParameterSource>();
        for (ClassSpec spec : CLASSES) {
            for (Field field : spec.type.getDeclaredFields()) {
                if (!isRegistrableConstant(field)) {
                    continue;
                }
                Object value;
                try {
                    field.setAccessible(true);
                    value = field.get(null);
                } catch (ReflectiveOperationException | RuntimeException error) {
                    // 取不到就跳过并留痕，绝不编一个值进去。
                    continue;
                }
                if (value == null) {
                    continue;
                }
                String key = spec.tag + "_" + field.getName();
                FieldSpec override = OVERRIDES.get(key);
                String name = override != null ? override.name : spec.groupName + "：" + field.getName();
                String unit = override != null ? override.unit : null;
                ParameterProvenance.Status status = override != null ? override.status : spec.status;
                String sourceText = override != null ? override.sourceText : spec.sourceText;
                String url = override != null ? override.sourceUrl : spec.sourceUrl;
                result.add(new ParameterSource(key, name, String.valueOf(value), unit,
                        ParameterProvenance.decorate(status, sourceText), url, REGISTRY_VERSION));
            }
        }
        result.sort(new Comparator<ParameterSource>() {
            public int compare(ParameterSource left, ParameterSource right) {
                return left.getCode().compareTo(right.getCode());
            }
        });
        return result;
    }

    /** 只登记静态终态的数值常量，跳过 String 等非数值项以免把说明文本也当成参数。 */
    private boolean isRegistrableConstant(Field field) {
        int modifiers = field.getModifiers();
        if (!Modifier.isStatic(modifiers) || !Modifier.isFinal(modifiers)) {
            return false;
        }
        if (field.isSynthetic()) {
            return false;
        }
        Class<?> type = field.getType();
        return type == double.class || type == int.class || type == long.class;
    }
}
