package com.example.Ece.agent.model;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Codes persisted in agent_device and used by the deterministic policy. */
public final class AgentDeviceCodes {
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
            IRRIGATION, VENTILATION, GROW_LIGHT, SHADE, ROOF_VENT,
            EXHAUST_FAN, COOLING_PAD, CIRCULATION_FAN, CO2_SUPPLY));

    private AgentDeviceCodes() {
    }

    public static List<String> all() {
        return ALL;
    }
}
