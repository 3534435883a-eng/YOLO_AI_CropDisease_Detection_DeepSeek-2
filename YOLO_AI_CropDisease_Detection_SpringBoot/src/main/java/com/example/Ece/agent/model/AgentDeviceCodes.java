package com.example.Ece.agent.model;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Codes persisted in agent_device and used by the deterministic policy. */
public final class AgentDeviceCodes {
    public static final String HEATING = "HEATING";
    public static final String IRRIGATION = "IRRIGATION";
    public static final String VENTILATION = "VENTILATION";
    // Keep the persisted code aligned with the existing Vue environment page.
    public static final String GROW_LIGHT = "SUPPLEMENTAL_LIGHT";
    public static final String SHADE = "SHADE";
    public static final String CO2_SUPPLY = "CO2_SUPPLY";
    public static final String ROOF_VENT = "ROOF_VENT";
    public static final String EXHAUST_FAN = "EXHAUST_FAN";
    public static final String COOLING_PAD = "COOLING_PAD";
    public static final String CIRCULATION_FAN = "CIRCULATION_FAN";

    private static final List<String> ALL = Collections.unmodifiableList(Arrays.asList(
            HEATING, IRRIGATION, VENTILATION, GROW_LIGHT, SHADE, ROOF_VENT,
            EXHAUST_FAN, COOLING_PAD, CIRCULATION_FAN, CO2_SUPPLY));

    private AgentDeviceCodes() {
    }

    public static List<String> all() {
        return ALL;
    }

    /**
     * 设备代码的短中文名，供智能体工具拼装证据文本与处方使用。
     *
     * <p>放在这里而不是各工具里各写一份：设备名属于设备词表本身，
     * 分散在多个工具里会随版本漂移（此前温室状态工具用服务端的长名
     * "端墙排风机/湿帘循环水泵"，处方工具用自己的短名"排风/湿帘"，同一台设备两个叫法）。</p>
     */
    public static String label(String code) {
        if (HEATING.equals(code)) return "加热系统（模拟）";
        if (IRRIGATION.equals(code)) {
            return "滴灌";
        }
        if (VENTILATION.equals(code)) {
            return "通风";
        }
        if (GROW_LIGHT.equals(code)) {
            return "补光";
        }
        if (SHADE.equals(code)) {
            return "遮阳";
        }
        if (CO2_SUPPLY.equals(code)) {
            return "CO₂补给";
        }
        if (ROOF_VENT.equals(code)) {
            return "屋窗";
        }
        if (EXHAUST_FAN.equals(code)) {
            return "排风";
        }
        if (COOLING_PAD.equals(code)) {
            return "湿帘";
        }
        if (CIRCULATION_FAN.equals(code)) {
            return "环流";
        }
        return code;
    }
}
