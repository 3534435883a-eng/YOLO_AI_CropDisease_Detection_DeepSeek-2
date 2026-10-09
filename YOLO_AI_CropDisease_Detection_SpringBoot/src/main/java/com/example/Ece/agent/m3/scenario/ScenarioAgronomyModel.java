package com.example.Ece.agent.m3.scenario;

import com.example.Ece.agent.profile.HortiM3Profile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Deterministic, uncalibrated agronomy response for a virtual 252 m2 planted zone.
 * Water is conserved in litres; theta is volumetric water content, not the legacy
 * relative substrate-moisture percentage. Disease outputs describe conditions,
 * never infection probability, diagnosed severity, or treatment efficacy.
 */
public final class ScenarioAgronomyModel {
    public static final String VERSION = "root-water-condition-proxy-20261008-v2";
    private static final String FAO_VPD_URL = "https://www.fao.org/4/X0490E/x0490e07.htm";
    private static final String FAO_WATER_URL = "https://www.fao.org/4/x0490e/x0490e0e.htm";
    private static final String GRAY_MOLD_URL = "https://extension.umn.edu/agriculture/specialty-crops/vegetable-farming/disease-management/gray-mold-of-tomatoes";
    private static final String LEAF_SPOT_URL = "https://extension.umn.edu/agriculture/specialty-crops/vegetable-farming/disease-management/early-blight-in-tomato-and-potato";
    private static final double AREA_M2 = HortiM3Profile.TOTAL_EXPERIMENT_AREA_M2;
    // Effective virtual storage layer; not the crop's maximum rooting depth.
    private static final double ROOT_DEPTH_M = HortiM3Profile.ASSUMED_EFFECTIVE_ROOT_DEPTH_M;
    private static final double ROOT_VOLUME_L = AREA_M2 * ROOT_DEPTH_M * 1000;
    private static final double INITIAL_THETA = .30, FIELD_CAPACITY = .34, SATURATION = .46;
    private static final double DRY_THRESHOLD = .24, WILTING_PROXY = .12, WET_THRESHOLD = .36;
    private static final double DRAINAGE_TIME_MIN = 120;
    private static final double IRRIGATION_L_MIN = 15.0, COOLING_WATER_L_MIN = 2.0, CO2_KG_MIN = .025;
    private static final double CANOPY_RH_THRESHOLD = 82, CANOPY_WET_THRESHOLD = .55;
    private static final double CANOPY_WETTING = .0015, CANOPY_DRYING_BASE = .002, CANOPY_VPD_DRYING = .006, FAN_DRYING = .012;
    private static final int INTEGRATION_MINUTES = 1, ROOT_HIGH_MINUTES = 120, COOL_HIGH_MINUTES = 360, WARM_HIGH_MINUTES = 240;
    private static final Map<String, Double> ELECTRIC_KW;
    static {
        Map<String, Double> p = new LinkedHashMap<>();
        p.put("HEATING", 40.0); p.put("IRRIGATION", .30); p.put("VENTILATION", .05);
        p.put("SUPPLEMENTAL_LIGHT", 12.0); p.put("SHADE", .02); p.put("CO2_SUPPLY", .05);
        p.put("ROOF_VENT", .03); p.put("EXHAUST_FAN", 1.20); p.put("COOLING_PAD", .75);
        p.put("CIRCULATION_FAN", .30); ELECTRIC_KW = Collections.unmodifiableMap(p);
    }
    private final ObjectMapper mapper;
    private double soilWaterL = INITIAL_THETA * ROOT_VOLUME_L;
    private double waterUsedL, drainageL, evapotranspirationL, wetExposureMinutes, dryExposureMinutes;
    private double continuousWetMinutes, continuousDryMinutes, canopyWetnessProxy, canopyWetMinutes;
    private double coolDiseaseMinutes, warmDiseaseMinutes, coolDiseaseExposure, warmDiseaseExposure;
    private double coolingWaterL, electricityKwh, co2Kg;
    private double lastIrrigationL, lastDrainageL, lastEtL, lastElectricityKwh, lastCoolingL, lastCo2Kg;
    private double lastVpd, lastTemperature = 22, lastHumidity = 72, lastEtRateMmDay;
    private long elapsedMinutes;
    private final Map<String, Double> deviceMinutes = new LinkedHashMap<>();

    public ScenarioAgronomyModel(ObjectMapper mapper) {
        this.mapper = mapper;
        for (String code : ELECTRIC_KW.keySet()) deviceMinutes.put(code, 0.0);
    }

    public void advance(JsonNode environment, Map<String, Double> effectiveDuty, int minutes) {
        if (minutes <= 0) return;
        lastTemperature = bounded(environment.path("temperatureC").asDouble(22), -5, 50);
        lastHumidity = bounded(environment.path("airHumidityPct").asDouble(72), 0, 100);
        double ppfd = bounded(environment.path("ppfd").asDouble(0), 0, 2200);
        lastVpd = Math.max(0, .6108 * Math.exp(17.27 * lastTemperature / (lastTemperature + 237.3)) * (1 - lastHumidity / 100));
        // Validate every duty before any mutable water/resource state changes.
        for (String code : ELECTRIC_KW.keySet()) power(effectiveDuty, code);
        lastIrrigationL = lastDrainageL = lastEtL = 0;
        // A fixed one-minute integration grid keeps the same constant-input
        // trajectory for 1-, 15- and 30-minute API calls. Exposure resolution is
        // one simulated minute, not a claim of measured leaf-wetness precision.
        for (int i = 0; i < minutes; i += INTEGRATION_MINUTES) advanceWaterAndConditions(ppfd, effectiveDuty, INTEGRATION_MINUTES);

        lastElectricityKwh = 0;
        for (Map.Entry<String, Double> p : ELECTRIC_KW.entrySet()) {
            double dutyMinutes = power(effectiveDuty, p.getKey()) * minutes;
            deviceMinutes.put(p.getKey(), deviceMinutes.getOrDefault(p.getKey(), 0.0) + dutyMinutes);
            lastElectricityKwh += p.getValue() * dutyMinutes / 60.0;
        }
        lastCoolingL = power(effectiveDuty, "COOLING_PAD") * COOLING_WATER_L_MIN * minutes;
        lastCo2Kg = power(effectiveDuty, "CO2_SUPPLY") * CO2_KG_MIN * minutes;
        electricityKwh += lastElectricityKwh; coolingWaterL += lastCoolingL; co2Kg += lastCo2Kg;
        elapsedMinutes += minutes;
    }

    private void advanceWaterAndConditions(double ppfd, Map<String, Double> effectiveDuty, int minutes) {
        // An instantaneous radiation/VPD evaporation proxy, explicitly not FAO-56 or M3 calibration.
        // Keep a 25% soil-evaporation proxy while limiting the transpiration share under root stress.
        lastEtRateMmDay = bounded(.25 + 3.4 * ppfd / 800 + .7 * lastVpd, .1, 7) * (.25 + .75 * waterStressFactor());
        double potentialEtL = lastEtRateMmDay * AREA_M2 * minutes / 1440.0;
        double irrigation = power(effectiveDuty, "IRRIGATION") * IRRIGATION_L_MIN * minutes;
        double available = soilWaterL + irrigation;
        double overflow = Math.max(0, available - SATURATION * ROOT_VOLUME_L);
        available -= overflow;
        double et = Math.min(available, potentialEtL);
        available -= et;
        double excess = Math.max(0, available - FIELD_CAPACITY * ROOT_VOLUME_L);
        double drainage = overflow + excess * (1 - Math.exp(-minutes / DRAINAGE_TIME_MIN));
        soilWaterL = Math.max(0, available - (drainage - overflow));
        waterUsedL += irrigation; drainageL += drainage; evapotranspirationL += et;
        lastIrrigationL += irrigation; lastDrainageL += drainage; lastEtL += et;
        double theta = soilWaterL / ROOT_VOLUME_L;
        boolean wet = theta > WET_THRESHOLD, dry = theta < DRY_THRESHOLD;
        continuousWetMinutes = wet ? continuousWetMinutes + minutes : 0;
        continuousDryMinutes = dry ? continuousDryMinutes + minutes : 0;
        if (wet) wetExposureMinutes += minutes;
        if (dry) dryExposureMinutes += minutes;

        // Canopy wetness is a dimensionless proxy. Circulation dries this proxy;
        // it does not invent measured leaf temperature, wind speed or mean RH changes.
        double wettingPerMinute = CANOPY_WETTING * Math.max(0, lastHumidity - CANOPY_RH_THRESHOLD);
        double dryingPerMinute = CANOPY_DRYING_BASE + CANOPY_VPD_DRYING * lastVpd + FAN_DRYING * power(effectiveDuty, "CIRCULATION_FAN");
        double rate = wettingPerMinute + dryingPerMinute;
        double equilibrium = wettingPerMinute / rate;
        canopyWetnessProxy = bounded(equilibrium + (canopyWetnessProxy - equilibrium) * Math.exp(-rate * minutes), 0, 1);
        boolean canopyWet = canopyWetnessProxy >= CANOPY_WET_THRESHOLD;
        if (canopyWet) canopyWetMinutes += minutes;
        boolean coolCondition = canopyWet && lastTemperature >= 15 && lastTemperature <= 25;
        boolean warmCondition = canopyWet && lastTemperature >= 20 && lastTemperature <= 30;
        coolDiseaseMinutes = coolCondition ? coolDiseaseMinutes + minutes : 0;
        warmDiseaseMinutes = warmCondition ? warmDiseaseMinutes + minutes : 0;
        if (coolCondition) coolDiseaseExposure += minutes;
        if (warmCondition) warmDiseaseExposure += minutes;

    }

    public double waterStressFactor() {
        double theta = soilWaterL / ROOT_VOLUME_L;
        double dryFactor = bounded((theta - WILTING_PROXY) / (DRY_THRESHOLD - WILTING_PROXY), 0, 1);
        double wetFactor = theta <= WET_THRESHOLD ? 1 : bounded(1 - .5 * (theta - WET_THRESHOLD) / (SATURATION - WET_THRESHOLD), .5, 1);
        return Math.min(dryFactor, wetFactor);
    }

    public ObjectNode snapshot() {
        ObjectNode o = mapper.createObjectNode();
        o.put("modelVersion", VERSION); o.put("origin", "SIMULATED_ROOT_WATER_BALANCE");
        o.put("calibrated", false); o.put("measured", false); o.put("soilMoistureUnit", "%vol");
        o.put("soilMoistureVwcPct", soilWaterL / ROOT_VOLUME_L * 100);
        o.put("rootWaterL", soilWaterL); o.put("rootVolumeL", ROOT_VOLUME_L);
        o.put("areaM2", AREA_M2); o.put("rootDepthM", ROOT_DEPTH_M);
        o.put("rootDepthMeaning", "虚拟有效储水层厚度，非番茄最大根深");
        o.put("integrationMinutes", INTEGRATION_MINUTES); o.put("vpdKpa", lastVpd);
        o.put("fieldCapacityVwcPct", FIELD_CAPACITY * 100); o.put("saturationVwcPct", SATURATION * 100);
        o.put("dryThresholdVwcPct", DRY_THRESHOLD * 100); o.put("wetThresholdVwcPct", WET_THRESHOLD * 100);
        o.put("waterStressFactor", waterStressFactor()); o.put("waterUsedL", waterUsedL);
        o.put("drainageL", drainageL); o.put("evapotranspirationL", evapotranspirationL);
        o.put("wetExposureMinutes", wetExposureMinutes); o.put("dryExposureMinutes", dryExposureMinutes);
        o.put("continuousWetMinutes", continuousWetMinutes); o.put("continuousDryMinutes", continuousDryMinutes);
        o.put("canopyWetnessProxy", canopyWetnessProxy); o.put("canopyWetMinutes", canopyWetMinutes);
        o.put("evapotranspirationRateMmDay", lastEtRateMmDay); o.put("elapsedMinutes", elapsedMinutes);
        o.put("waterBalanceResidualL", INITIAL_THETA * ROOT_VOLUME_L + waterUsedL - drainageL - evapotranspirationL - soilWaterL);
        String root = continuousWetMinutes > 0 ? "WET" : continuousDryMinutes > 0 ? "DRY" : "SUITABLE";
        o.put("rootCondition", root);
        ArrayNode conditions = o.putArray("diseaseConditions");
        disease(conditions, "COOL_WET", "灰霉病适生环境提示", coolDiseaseMinutes, coolDiseaseExposure, COOL_HIGH_MINUTES,
            "15–25°C且冠层湿润代理≥0.55", "观察花、叶、果是否有灰色霉层；有症状时补拍照片并人工确认");
        disease(conditions, "WARM_LEAF_WET", "叶斑类病害湿润条件提示", warmDiseaseMinutes, warmDiseaseExposure, WARM_HIGH_MINUTES,
            "20–30°C且冠层湿润代理≥0.55", "复查病斑是否扩展；病叶处置和病因判断需要人工登记");
        o.put("diseaseConditionLevel", diseaseLevel());
        o.put("riskLevel", "HIGH".equals(diseaseLevel()) || continuousWetMinutes >= ROOT_HIGH_MINUTES || continuousDryMinutes >= ROOT_HIGH_MINUTES
            ? "HIGH" : continuousWetMinutes > 0 || continuousDryMinutes > 0 || "MEDIUM".equals(diseaseLevel()) ? "MEDIUM" : "LOW");
        ArrayNode notes = o.putArray("notes");
        notes.add("252m²为试验全部小区面积参考，不是CK单处理面积；0.25m是虚拟有效储水层厚度，不是最大根深；体积含水率、设备流量和响应系数均为未标定场景假设。");
        notes.add("体积含水率与旧版62%的相对含水读数不是同一量纲；本模型不以M3株高反推实测土壤水分。");
        notes.add("蒸散由瞬时PPFD、空气VPD和根区胁迫估算；土壤蒸发份额假设25%；排水用两小时时间常数，未包含基质分层、肥液EC和养分吸收。");
        notes.add("冠层湿润仅为湿度与环流的无量纲代理；病害提示为适生条件规则，不是感染概率或病斑面积。");
        notes.add("灰霉6小时参考湿叶条件资料设置观察提醒；叶斑4小时仅为工程复查窗口，没有统一病原感染阈值含义；持续暴露按1分钟积分。");
        notes.add("未干预分支从相同初值接受相同事件，模拟差异不能替代现场验证。");
        return o;
    }

    public ObjectNode resources() {
        ObjectNode o = mapper.createObjectNode();
        o.put("origin", "SIMULATED_DEVICE_ACCOUNTING"); o.put("calibrated", false);
        o.put("irrigationWaterL", waterUsedL); o.put("coolingWaterL", coolingWaterL);
        o.put("totalWaterL", waterUsedL + coolingWaterL); o.put("electricityKwh", electricityKwh); o.put("co2Kg", co2Kg);
        o.put("waterUsedL", waterUsedL + coolingWaterL); o.put("energyKwh", electricityKwh);
        o.set("deviceDutyMinutes", mapper.valueToTree(deviceMinutes));
        ObjectNode last = o.putObject("lastStep");
        last.put("irrigationWaterL", lastIrrigationL); last.put("drainageL", lastDrainageL); last.put("evapotranspirationL", lastEtL);
        last.put("coolingWaterL", lastCoolingL); last.put("electricityKwh", lastElectricityKwh); last.put("co2Kg", lastCo2Kg);
        o.put("note", "按设备出力×模拟分钟与假设容量计量；不是电表、水表或费用实测。");
        return o;
    }

    public void appendParameters(ArrayNode p) {
        parameter(p, "rootDepth", ROOT_DEPTH_M, "m有效储水层"); parameter(p, "initialRootVwc", INITIAL_THETA * 100, "%vol");
        parameter(p, "fieldCapacity", FIELD_CAPACITY * 100, "%vol"); parameter(p, "saturation", SATURATION * 100, "%vol");
        parameter(p, "rootDryThreshold", DRY_THRESHOLD * 100, "%vol"); parameter(p, "rootWetThreshold", WET_THRESHOLD * 100, "%vol");
        parameter(p, "wiltingProxy", WILTING_PROXY * 100, "%vol"); parameter(p, "drainageTimeConstant", DRAINAGE_TIME_MIN, "min");
        parameter(p, "irrigationFlow", IRRIGATION_L_MIN, "L/min·满出力"); parameter(p, "coolingWaterFlow", COOLING_WATER_L_MIN, "L/min·满出力");
        parameter(p, "co2MassFlow", CO2_KG_MIN, "kg/min·满出力");
        parameter(p, "etBase", .25, "mm/day"); parameter(p, "etLightCoefficient", 3.4, "mm/day per 800 PPFD");
        parameter(p, "etVpdCoefficient", .7, "mm/day per kPa"); parameter(p, "etMaximum", 7, "mm/day");
        parameter(p, "etMinimum", .1, "mm/day"); parameter(p, "waterStressDryFloor", 0, "比例");
        parameter(p, "etSoilEvaporationFraction", .25, "比例");
        parameter(p, "waterStressWetFloor", .5, "比例"); parameter(p, "rootHighRiskExposure", ROOT_HIGH_MINUTES, "min连续");
        parameter(p, "canopyWettingCoefficient", CANOPY_WETTING, "/min per RH point above 82%");
        parameter(p, "canopyWettingRhThreshold", CANOPY_RH_THRESHOLD, "%"); parameter(p, "initialCanopyWetnessProxy", 0, "无量纲");
        parameter(p, "canopyDryingBase", CANOPY_DRYING_BASE, "/min"); parameter(p, "canopyVpdDrying", CANOPY_VPD_DRYING, "/min per kPa");
        parameter(p, "circulationDryingProxy", FAN_DRYING, "/min·满出力"); parameter(p, "canopyWetThreshold", CANOPY_WET_THRESHOLD, "无量纲");
        parameter(p, "coolWetHighExposure", COOL_HIGH_MINUTES, "min连续"); parameter(p, "warmWetHighExposure", WARM_HIGH_MINUTES, "min连续");
        parameter(p, "coolWetTemperatureLow", 15, "°C"); parameter(p, "coolWetTemperatureHigh", 25, "°C");
        parameter(p, "warmWetTemperatureLow", 20, "°C"); parameter(p, "warmWetTemperatureHigh", 30, "°C");
        parameter(p, "integrationStep", INTEGRATION_MINUTES, "min数值积分");
        ObjectNode area = p.addObject(); area.put("name", "virtualPlantedArea"); area.put("value", AREA_M2); area.put("unit", "m²");
        area.put("source", "PUBLISHED_EXPERIMENT_GEOMETRY"); area.put("calibrated", false); area.put("referenceUrl", HortiM3Profile.PAPER_URL);
        area.put("scope", "全部14个试验小区面积，用作虚拟资源账本规模；非CK单处理或温室总面积");
        for (Map.Entry<String, Double> power : ELECTRIC_KW.entrySet()) parameter(p, power.getKey() + "Power", power.getValue(), "kW·满出力");
    }

    public ObjectNode checkpoint() {
        ObjectNode o = snapshot(); o.set("resources", resources());
        o.put("coolDiseaseMinutes", coolDiseaseMinutes); o.put("warmDiseaseMinutes", warmDiseaseMinutes);
        o.put("coolDiseaseExposure", coolDiseaseExposure); o.put("warmDiseaseExposure", warmDiseaseExposure);
        o.put("lastTemperature", lastTemperature); o.put("lastHumidity", lastHumidity); o.put("lastVpd", lastVpd);
        return o;
    }

    public void restoreCheckpoint(JsonNode o) {
        if (!VERSION.equals(o.path("modelVersion").asText())) throw new IllegalArgumentException("农艺模型存档版本不匹配");
        if (Double.compare(number(o, "rootVolumeL"), ROOT_VOLUME_L) != 0 || Double.compare(number(o, "areaM2"), AREA_M2) != 0
            || Double.compare(number(o, "rootDepthM"), ROOT_DEPTH_M) != 0) throw new IllegalArgumentException("农艺存档根区参数不匹配");
        soilWaterL = number(o, "rootWaterL");
        if (soilWaterL > SATURATION * ROOT_VOLUME_L + 1e-6) throw new IllegalArgumentException("存档根区水量超出孔隙容量");
        waterUsedL = number(o, "waterUsedL"); drainageL = number(o, "drainageL"); evapotranspirationL = number(o, "evapotranspirationL");
        wetExposureMinutes = number(o, "wetExposureMinutes"); dryExposureMinutes = number(o, "dryExposureMinutes");
        continuousWetMinutes = number(o, "continuousWetMinutes"); continuousDryMinutes = number(o, "continuousDryMinutes");
        canopyWetnessProxy = number(o, "canopyWetnessProxy");
        if (canopyWetnessProxy > 1) throw new IllegalArgumentException("存档冠层湿润代理越界");
        canopyWetMinutes = number(o, "canopyWetMinutes");
        coolDiseaseMinutes = number(o, "coolDiseaseMinutes"); warmDiseaseMinutes = number(o, "warmDiseaseMinutes");
        coolDiseaseExposure = number(o, "coolDiseaseExposure"); warmDiseaseExposure = number(o, "warmDiseaseExposure");
        lastTemperature = finite(o, "lastTemperature"); lastHumidity = number(o, "lastHumidity"); lastVpd = number(o, "lastVpd");
        lastEtRateMmDay = number(o, "evapotranspirationRateMmDay"); elapsedMinutes = o.path("elapsedMinutes").asLong(-1);
        if (elapsedMinutes < 0) throw new IllegalArgumentException("存档模拟时长无效");
        if (lastTemperature < -5 || lastTemperature > 50 || lastHumidity > 100
            || wetExposureMinutes > elapsedMinutes || dryExposureMinutes > elapsedMinutes || canopyWetMinutes > elapsedMinutes
            || continuousWetMinutes > wetExposureMinutes || continuousDryMinutes > dryExposureMinutes
            || coolDiseaseMinutes > coolDiseaseExposure || warmDiseaseMinutes > warmDiseaseExposure
            || coolDiseaseExposure > canopyWetMinutes || warmDiseaseExposure > canopyWetMinutes)
            throw new IllegalArgumentException("农艺存档环境或暴露时长越界");
        JsonNode r = o.path("resources"), last = r.path("lastStep");
        coolingWaterL = number(r, "coolingWaterL"); electricityKwh = number(r, "electricityKwh"); co2Kg = number(r, "co2Kg");
        lastIrrigationL = number(last, "irrigationWaterL"); lastDrainageL = number(last, "drainageL"); lastEtL = number(last, "evapotranspirationL");
        lastCoolingL = number(last, "coolingWaterL"); lastElectricityKwh = number(last, "electricityKwh"); lastCo2Kg = number(last, "co2Kg");
        double residual = INITIAL_THETA * ROOT_VOLUME_L + waterUsedL - drainageL - evapotranspirationL - soilWaterL;
        if (Math.abs(residual) > 1e-4) throw new IllegalArgumentException("农艺存档水量不守恒");
        deviceMinutes.clear();
        double accountedElectricity = 0;
        for (String code : ELECTRIC_KW.keySet()) {
            double recordedMinutes = number(r.path("deviceDutyMinutes"), code);
            if (recordedMinutes > elapsedMinutes + 1e-6) throw new IllegalArgumentException("农艺存档设备时长越界");
            deviceMinutes.put(code, recordedMinutes); accountedElectricity += ELECTRIC_KW.get(code) * recordedMinutes / 60.0;
        }
        if (Math.abs(waterUsedL - IRRIGATION_L_MIN * deviceMinutes.get("IRRIGATION")) > 1e-4
            || Math.abs(coolingWaterL - COOLING_WATER_L_MIN * deviceMinutes.get("COOLING_PAD")) > 1e-4
            || Math.abs(co2Kg - CO2_KG_MIN * deviceMinutes.get("CO2_SUPPLY")) > 1e-6
            || Math.abs(electricityKwh - accountedElectricity) > 1e-6)
            throw new IllegalArgumentException("农艺存档资源与设备时段账本不一致");
    }

    private String diseaseLevel() {
        return coolDiseaseMinutes >= COOL_HIGH_MINUTES || warmDiseaseMinutes >= WARM_HIGH_MINUTES ? "HIGH" : coolDiseaseMinutes > 0 || warmDiseaseMinutes > 0 ? "MEDIUM" : "LOW";
    }
    private void disease(ArrayNode list, String code, String title, double continuous, double total, int high, String condition, String review) {
        ObjectNode d = list.addObject(); d.put("code", code); d.put("title", title);
        d.put("level", continuous >= high ? "HIGH" : continuous > 0 ? "MEDIUM" : "LOW");
        d.put("continuousExposureMinutes", continuous); d.put("cumulativeExposureMinutes", total);
        d.put("condition", condition); d.put("review", review); d.put("origin", "SIMULATED_CONDITION_RULE");
        d.put("validatedProbability", false); d.put("diagnosis", false); d.put("calibrated", false);
        d.put("alertAfterMinutes", high); d.put("thresholdPurpose", "CONDITION_REVIEW_WINDOW");
        d.put("referenceUrl", "COOL_WET".equals(code) ? GRAY_MOLD_URL : LEAF_SPOT_URL);
        d.put("referenceScope", "COOL_WET".equals(code) ? "文献支持温湿/湿叶方向，项目代理与6小时提醒未作病原感染验证"
            : "文献仅支持暖湿环境相关性；4小时为项目复查窗口，不是叶斑病原统一感染阈值");
    }
    private void parameter(ArrayNode list, String key, double value, String unit) {
        ObjectNode p = list.addObject(); p.put("name", key); p.put("value", value); p.put("unit", unit);
        p.put("source", "PROJECT_ENGINEERING_ASSUMPTION"); p.put("calibrated", false);
        p.put("scope", "仅用于虚拟场景，不是实测或M3标定参数");
        p.put("admissibleMin", 0); p.put("rangeMeaning", "数值安全范围，不是农艺推荐区间");
        if (unit.startsWith("%")) p.put("admissibleMax", 100);
        else if (unit.equals("比例") || unit.equals("无量纲")) p.put("admissibleMax", 1);
        if (key.startsWith("coolWet")) { p.put("referenceUrl", GRAY_MOLD_URL); p.put("referenceScope", "温湿/湿叶条件方向参考；具体代理及预警窗口未验证"); }
        else if (key.startsWith("warmWet")) { p.put("referenceUrl", LEAF_SPOT_URL); p.put("referenceScope", "暖湿关联参考；数值为工程复查窗口"); }
        else if (key.startsWith("et") || key.equals("canopyVpdDrying")) { p.put("referenceUrl", FAO_VPD_URL); p.put("referenceScope", "仅VPD定义与水分方向参考；ET系数不来自FAO Penman–Monteith"); }
        else if (key.equals("rootDepth") || key.equals("fieldCapacity") || key.equals("wiltingProxy")) { p.put("referenceUrl", FAO_WATER_URL); p.put("referenceScope", "储水量定义参考；0.25m为虚拟层厚，不采用FAO最大根深表作实测"); }
    }
    private static double power(Map<String, Double> duties, String code) { return bounded(duties.getOrDefault(code, 0.0), 0, 1); }
    private static double bounded(double value, double min, double max) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("农艺模型输入不是有限数值");
        return Math.max(min, Math.min(max, value));
    }
    private static double finite(JsonNode o, String name) {
        JsonNode v = o.get(name);
        if (v == null || !v.isNumber() || !Double.isFinite(v.asDouble())) throw new IllegalArgumentException("农艺存档字段无效: " + name);
        return v.asDouble();
    }
    private static double number(JsonNode o, String name) {
        double n = finite(o, name); if (n < 0) throw new IllegalArgumentException("农艺存档字段为负: " + name); return n;
    }
}
