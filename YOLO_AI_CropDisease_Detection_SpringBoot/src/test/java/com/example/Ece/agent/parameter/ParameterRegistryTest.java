package com.example.Ece.agent.parameter;

import com.example.Ece.agent.engine.TomatoSimulationEngine;
import com.example.Ece.agent.eval.ResourceRates;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 参数出处登记的测试。
 *
 * <p>核心不变量：**登记值必须等于源码里的真值**（否则登记表自己成了新的不可信来源）、
 * **未校准假设不得被标成已核实**、**覆盖不全之处要如实披露**。</p>
 */
class ParameterRegistryTest {

    private final ParameterRegistry registry = new ParameterRegistry();

    private Map<String, ParameterSource> byCode() {
        Map<String, ParameterSource> map = new LinkedHashMap<String, ParameterSource>();
        for (ParameterSource source : registry.enumerate()) {
            map.put(source.getCode(), source);
        }
        return map;
    }

    @Test
    void enumeratesEveryParameterClassWithCompleteCoreFields() {
        List<ParameterSource> sources = registry.enumerate();
        assertTrue(sources.size() > 50, "参数总数应在 50 条以上，实测 " + sources.size());

        List<String> incomplete = new ArrayList<String>();
        for (ParameterSource source : sources) {
            if (isBlank(source.getCode()) || isBlank(source.getName()) || isBlank(source.getValueText())
                    || isBlank(source.getSourceName()) || isBlank(source.getVersion())) {
                incomplete.add(source.getCode());
            }
        }
        assertTrue(incomplete.isEmpty(), "以下登记项缺核心字段：" + incomplete);
    }

    @Test
    void registeredValuesEqualTheSourceConstants() {
        Map<String, ParameterSource> map = byCode();
        // 抽几个不同类型/可见性/来源的常量，逐个与源码真值比对
        assertEquals(String.valueOf(TomatoSimulationEngine.IRRIGATION_L_PER_TICK),
                map.get("ENG_IRRIGATION_L_PER_TICK").getValueText());
        assertEquals(String.valueOf(TomatoSimulationEngine.COOLING_PAD_EFFICIENCY),
                map.get("ENG_COOLING_PAD_EFFICIENCY").getValueText());
        assertEquals(String.valueOf(TomatoSimulationEngine.PSYCHROMETRIC_CONSTANT_KPA_PER_C),
                map.get("ENG_PSYCHROMETRIC_CONSTANT_KPA_PER_C").getValueText());
        assertEquals(String.valueOf(ResourceRates.IRRIGATION_M3_PER_STEP),
                map.get("RATE_IRRIGATION_M3_PER_STEP").getValueText());
    }

    @Test
    void privateConstantsAreCoveredTooNotJustPublicOnes() {
        Map<String, ParameterSource> map = byCode();
        // 这两个是 private static final，反射应能取到——否则棚体几何就漏登记了
        assertNotNull(map.get("ENG_BED_ROOT_VOLUME_L"), "私有常量未被登记");
        assertNotNull(map.get("ENG_GREENHOUSE_AIR_VOLUME_M3"), "私有常量未被登记");
    }

    @Test
    void uncalibratedAssumptionsAreNeverMarkedVerified() {
        Map<String, ParameterSource> map = byCode();
        String[] assumptions = {
                "ENG_BASE_HEAT_EXCHANGE_PER_TICK",
                "ENG_BASE_VAPOR_EXCHANGE_PER_TICK",
                "ENG_VENTILATION_EXCHANGE_PER_TICK",
                "ENG_ROOF_VENT_EXCHANGE_PER_TICK",
                "ENG_EXHAUST_FAN_EXCHANGE_PER_TICK",
                "ENG_COOLING_PAD_EFFICIENCY",
        };
        for (String code : assumptions) {
            ParameterSource source = map.get(code);
            assertNotNull(source, "缺少登记：" + code);
            assertEquals(ParameterProvenance.Status.UNVERIFIED_LITERATURE, source.getStatus(),
                    code + " 是未校准假设，绝不能被标成可引用");
            assertTrue(source.getSourceUrl() == null || source.getSourceUrl().isEmpty(),
                    code + " 不应带出处链接");
        }
    }

    @Test
    void placeholderClassesAreMarkedAsPlaceholderNotLiterature() {
        Map<String, ParameterSource> map = byCode();
        // 经济与资源速率不是"待核对文献"，而是"需换成当地实际数据"——两种缺口补救方式不同
        assertEquals(ParameterProvenance.Status.PLACEHOLDER,
                map.get("RATE_IRRIGATION_M3_PER_STEP").getStatus());
        assertEquals(ParameterProvenance.Status.UNVERIFIED_LITERATURE,
                map.get("SOIL_PH_MIN").getStatus());
    }

    @Test
    void theOneFaoBackedConstantIsRegisteredAsVerifiedWithUrl() {
        ParameterSource gamma = byCode().get("ENG_PSYCHROMETRIC_CONSTANT_KPA_PER_C");
        assertEquals(ParameterProvenance.Status.VERIFIED, gamma.getStatus());
        assertTrue(gamma.getSourceUrl().contains("fao.org"), "应带 FAO-56 出处链接");
        assertTrue(gamma.getName().contains("γ"), "应给出可读的参数名");
    }

    @Test
    void enumerationIsDeterministicSoResultsAreReproducible() {
        List<ParameterSource> first = registry.enumerate();
        List<ParameterSource> second = registry.enumerate();
        assertEquals(first.size(), second.size());
        for (int index = 0; index < first.size(); index++) {
            assertEquals(first.get(index).getCode(), second.get(index).getCode(),
                    "第 " + index + " 项顺序不稳定，登记结果将无法复现");
        }
    }

    @Test
    void coverageGapsAreDisclosedInsteadOfHidden() {
        assertFalse(ParameterRegistry.UNCOVERED_NOTE.isEmpty(), "必须披露未覆盖的内联字面量");
        String note = String.join(" ", ParameterRegistry.UNCOVERED_NOTE);
        assertTrue(note.contains("TomatoSimulationEngine"), "应点名未覆盖的引擎内联常量");
        assertTrue(note.contains("TomatoDecisionPolicy"), "应点名规则层阈值未纳入覆盖");
    }

    /**
     * 编码必须能塞进表里，且不得重复。
     *
     * <p>2026-09-26 实测踩过：初版编码用「完整类名.字段名」，
     * {@code TomatoSimulationEngine.PSYCHROMETRIC_CONSTANT_KPA_PER_C} 超出
     * {@code parameter_code VARCHAR(64)}，刷新直接
     * {@code Data too long for column 'parameter_code'} → 接口 500。
     * 编码风格也本就该是 {@code SOIL_PH_MIN} 这类短码。</p>
     *
     * <p>唯一性同样关键：表上唯一键是 {@code (parameter_code, version)}，
     * 编码撞了就会**静默覆盖**另一条登记。</p>
     */
    @Test
    void codesFitTheColumnAndAreUnique() {
        List<ParameterSource> sources = registry.enumerate();
        Map<String, Integer> seen = new LinkedHashMap<String, Integer>();
        for (ParameterSource source : sources) {
            String code = source.getCode();
            assertTrue(code.length() <= 64,
                    "编码超长（" + code.length() + " > 64）：" + code + "，会写入失败");
            Integer previous = seen.put(code, Integer.valueOf(1));
            assertNull(previous, "编码重复，唯一键会静默覆盖：" + code);
        }
        assertEquals(sources.size(), seen.size());
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
