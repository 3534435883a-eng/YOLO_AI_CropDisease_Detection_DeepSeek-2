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

    private static final List<String> ALL = Collections.unmodifiableList(Arrays.asList(
            IRRIGATION, VENTILATION, GROW_LIGHT, SHADE, CO2_SUPPLY));

    private AgentDeviceCodes() {
    }

    public static List<String> all() {
        return ALL;
    }
}
