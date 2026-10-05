package com.example.Ece.agent.profile;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Published facts and explicit scenario assumptions for the Horti-M3-Tomato
 * demonstration profile. Values here describe the public experiment, not a
 * calibrated parameter set or live greenhouse telemetry.
 */
public final class HortiM3Profile {
    public static final String ID = "HORTI_M3_TOMATO";
    public static final String DISPLAY_NAME = "Horti-M3 番茄场景";
    public static final String SOURCE_URL = "https://doi.org/10.5281/zenodo.17217565";
    public static final String PAPER_URL = "https://www.nature.com/articles/s41597-026-07074-w";
    public static final String SOURCE_STATUS = "SIMULATED_UNCALIBRATED";

    public static final int DEFAULT_YEAR = 2025;
    public static final LocalDate DEFAULT_START_DATE = LocalDate.of(2025, 4, 19);
    public static final LocalDate DEFAULT_END_DATE = LocalDate.of(2025, 6, 13);
    public static final int DEFAULT_DAYS = 56;
    public static final String DEFAULT_CULTIVAR_CODE = "GUANGHUI_201";
    public static final String DEFAULT_CULTIVAR_NAME = "广辉201";
    public static final String DEFAULT_TREATMENT_CODE = "CK";
    public static final int SENSOR_INTERVAL_MINUTES = 30;
    public static final int AGENT_TICK_MINUTES = SENSOR_INTERVAL_MINUTES;
    public static final int AGENT_STEPS_PER_DAY = 24 * 60 / AGENT_TICK_MINUTES;

    public static final double GREENHOUSE_LENGTH_M = 40.0;
    public static final double GREENHOUSE_WIDTH_M = 40.0;
    public static final double GREENHOUSE_FOOTPRINT_M2 = GREENHOUSE_LENGTH_M * GREENHOUSE_WIDTH_M;
    public static final double EAVE_HEIGHT_M = 4.5;
    public static final double RIDGE_HEIGHT_M = 6.0;
    public static final int EXPERIMENT_PLOT_COUNT = 14;
    public static final int TREATMENT_COUNT = 7;
    public static final int REPLICATE_COUNT = 2;
    public static final double PLOT_AREA_M2 = 18.0;
    public static final double TOTAL_EXPERIMENT_AREA_M2 = EXPERIMENT_PLOT_COUNT * PLOT_AREA_M2;
    public static final int PLANTS_PER_PLOT = 60;
    public static final int TOTAL_EXPERIMENT_PLANTS = EXPERIMENT_PLOT_COUNT * PLANTS_PER_PLOT;
    public static final int RIDGES_PER_PLOT = 2;
    public static final double IN_ROW_SPACING_M = 0.40;
    public static final double BETWEEN_ROW_SPACING_M = 0.60;
    public static final int TOPPING_AFTER_TRUSS = 6;

    /** Assumption retained only because the legacy water-balance model needs a root-zone volume. */
    public static final double ASSUMED_EFFECTIVE_ROOT_DEPTH_M = 0.25;
    public static final double ASSUMED_INITIAL_AIR_TEMPERATURE_C = 22.0;
    public static final double ASSUMED_INITIAL_RELATIVE_HUMIDITY_PCT = 72.0;
    public static final double ASSUMED_INITIAL_SUBSTRATE_MOISTURE_PCT = 62.0;
    public static final double ASSUMED_INITIAL_CO2_PPM = 600.0;

    private HortiM3Profile() {
    }

    /** API metadata for UI attribution; no raw observation values are included. */
    public static Map<String, Object> metadata() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("profileId", ID);
        result.put("displayName", DISPLAY_NAME);
        result.put("year", Integer.valueOf(DEFAULT_YEAR));
        result.put("startDate", DEFAULT_START_DATE.toString());
        result.put("endDate", DEFAULT_END_DATE.toString());
        result.put("days", Integer.valueOf(DEFAULT_DAYS));
        result.put("cultivarCode", DEFAULT_CULTIVAR_CODE);
        result.put("cultivar", DEFAULT_CULTIVAR_NAME);
        result.put("treatment", DEFAULT_TREATMENT_CODE);
        result.put("sampleIntervalMinutes", Integer.valueOf(SENSOR_INTERVAL_MINUTES));
        result.put("greenhouseLengthM", Double.valueOf(GREENHOUSE_LENGTH_M));
        result.put("greenhouseWidthM", Double.valueOf(GREENHOUSE_WIDTH_M));
        result.put("greenhouseFootprintM2", Double.valueOf(GREENHOUSE_FOOTPRINT_M2));
        result.put("eaveHeightM", Double.valueOf(EAVE_HEIGHT_M));
        result.put("ridgeHeightM", Double.valueOf(RIDGE_HEIGHT_M));
        result.put("plotCount", Integer.valueOf(EXPERIMENT_PLOT_COUNT));
        result.put("plotAreaM2", Double.valueOf(PLOT_AREA_M2));
        result.put("experimentAreaM2", Double.valueOf(TOTAL_EXPERIMENT_AREA_M2));
        result.put("plantsPerPlot", Integer.valueOf(PLANTS_PER_PLOT));
        result.put("plantCount", Integer.valueOf(TOTAL_EXPERIMENT_PLANTS));
        result.put("ridgesPerPlot", Integer.valueOf(RIDGES_PER_PLOT));
        result.put("inRowSpacingM", Double.valueOf(IN_ROW_SPACING_M));
        result.put("betweenRowSpacingM", Double.valueOf(BETWEEN_ROW_SPACING_M));
        result.put("toppingAfterTruss", Integer.valueOf(TOPPING_AFTER_TRUSS));
        result.put("sourceStatus", SOURCE_STATUS);
        result.put("sourceUrl", SOURCE_URL);
        result.put("paperUrl", PAPER_URL);
        result.put("calibrated", Boolean.FALSE);
        result.put("cohortScope", "CK/广辉201 仅为参数参考标签；面积和株数为全部试验小区规模，不是 CK 单处理样本汇总");
        result.put("initialPhenologyStatus", "ASSUMED_UNCALIBRATED");
        result.put("weatherStatus", "SYNTHETIC_WITH_STRESS_WINDOWS");
        List<String> assumptions = new ArrayList<String>();
        assumptions.add("未导入公开原始环境/表型数据；当前结果为确定性模拟");
        assumptions.add("多跨屋架、设备容量、控制响应与环境轨迹为示意假设");
        assumptions.add("根区有效深度 0.25 m 为水分模型假设，不是数据集给定值");
        assumptions.add("模型 PPFD 为代理量；论文 lux 字段未转换为实测 PPFD");
        result.put("assumptions", assumptions);
        return result;
    }
}
