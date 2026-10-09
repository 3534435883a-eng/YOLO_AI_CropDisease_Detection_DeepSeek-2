package com.example.Ece.agent.m3.scenario;

import com.example.Ece.agent.crop.TomatoCropGrowthModel;
import com.example.Ece.agent.crop.TomatoCropState;
import com.example.Ece.agent.crop.CropStage;
import com.example.Ece.agent.model.SimulationState;
import com.example.Ece.agent.m3.M3LiveRiskEngine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import java.time.LocalDateTime;
import java.util.*;

/** A counterfactual greenhouse, anchored to indoor M3 changes, never labelled as measured weather. */
public final class ScenarioSession {
    private static final String RESPONSE_VERSION="m3-scenario-agronomy-20261008-v4";
    private static final String[] CODES={"HEATING","IRRIGATION","VENTILATION","SUPPLEMENTAL_LIGHT","SHADE","CO2_SUPPLY","ROOF_VENT","EXHAUST_FAN","COOLING_PAD","CIRCULATION_FAN"};
    private static final String[] WEATHER={"STORM","COLD_SNAP","HEAT_WAVE","STRONG_WIND","OVERCAST","NIGHT_HUMIDITY"};
    private static final double HEAT_GAIN=1.8, COOL_GAIN=2.0, SOLAR_GAIN=1.2,
        AIR_EXCHANGE=.14, VENT_EXCHANGE=.35, WATER_EXCHANGE=.12, VENT_WATER_EXCHANGE=.32,
        DRY_AIR_PROXY=1.6, PAD_WATER_GAIN=.8, CO2_GAIN=100, CO2_EXCHANGE=.12,
        VENT_CO2_EXCHANGE=.5, SHADE_FRACTION=.55, LIGHT_GAIN=150, LUX_TO_PPFD=.0185;
    private final ObjectMapper mapper;
    private final CheckpointRandom random;
    private final long seed;
    private final String runId;
    private final TomatoCropGrowthModel crop;
    private final double linear;
    private TomatoCropState cropState,shadowCrop;
    private final M3LiveRiskEngine risks,shadowRisks;
    private final ScenarioAgronomyModel agronomy,shadowAgronomy;
    private final Map<String,Double> duty=new LinkedHashMap<>();
    private final Map<String,Boolean> health=new LinkedHashMap<>();
    private final Map<String,Integer> until=new HashMap<>();
    private final List<ObjectNode> log=new ArrayList<>();
    private final List<ObjectNode> trends=new ArrayList<>();
    private final Set<String> requestIds=new LinkedHashSet<>();
    private ObjectNode reference,environment,shadow,weather,decision,risk,shadowRisk;
    private LocalDateTime at;
    private long version;
    private int tick,nextEvent=4,eventSerial,decisionCalls,observeTick=-1;
    private boolean autoEvents=true,autoActuation=true,pending,closed;
    private double tDelta,waterDelta,co2Delta,shadowT,shadowWater,shadowCo2,heightDelta,shadowHeight,lastReferenceHeight;
    private String previousAgronomyRisk="LOW";
    private String farmTaskId;

    public ScenarioSession(String runId,long seed,ObjectMapper mapper,TomatoCropGrowthModel crop,
                           TomatoCropState initial,double linear,JsonNode initialFrame) {
        this.runId=runId;this.seed=seed;this.mapper=mapper;this.random=new CheckpointRandom(seed);this.crop=crop;
        this.cropState=initial;this.shadowCrop=initial;this.linear=linear;
        risks=new M3LiveRiskEngine(mapper);shadowRisks=new M3LiveRiskEngine(mapper);
        agronomy=new ScenarioAgronomyModel(mapper);shadowAgronomy=new ScenarioAgronomyModel(mapper);
        for(String c:CODES){duty.put(c,0.0);health.put(c,true);}
        reference=(ObjectNode)initialFrame.deepCopy();at=LocalDateTime.parse(reference.path("at").asText());
        lastReferenceHeight=reference.path("correctedHeightCm").asDouble();
        environment=buildEnvironment(reference.path("environment"),false,0);shadow=environment.deepCopy();
        risk=risks.evaluate(environment,0);shadowRisk=shadowRisks.evaluate(shadow,0);
        decision=mapper.createObjectNode();decision.put("status","IDLE");
    }

    public synchronized ObjectNode advance(JsonNode frame) {
        reference=(ObjectNode)frame.deepCopy();at=LocalDateTime.parse(frame.path("at").asText());tick++;version++;
        for(String c:CODES)if(until.getOrDefault(c,Integer.MAX_VALUE)<tick){duty.put(c,0.0);until.remove(c);record("ACTION_EXPIRED",c+" 仿真动作有效期结束");}
        if(weather!=null&&tick>=weather.path("endTick").asInt())endWeather();
        if(autoEvents&&weather==null&&tick>=nextEvent) {
            String type=WEATHER[random.nextInt(WEATHER.length)];
            if("NIGHT_HUMIDITY".equals(type)&&at.getHour()>=6&&at.getHour()<18)type="OVERCAST";
            trigger(type);
            if(random.nextDouble()<0.1&&health.values().stream().allMatch(Boolean::booleanValue))trigger("FAULT");
        }
        enforceRuntimeInterlocks();
        environment=buildEnvironment(frame.path("environment"),false,30);
        shadow=buildEnvironment(frame.path("environment"),true,30);
        agronomy.advance(environment,effectiveDuties(),30);
        shadowAgronomy.advance(shadow,Collections.emptyMap(),30);
        environment.put("soilMoistureVwcPct",agronomy.snapshot().path("soilMoistureVwcPct").asDouble());
        environment.put("rootWaterStressFactor",agronomy.waterStressFactor());
        shadow.put("soilMoistureVwcPct",shadowAgronomy.snapshot().path("soilMoistureVwcPct").asDouble());
        shadow.put("rootWaterStressFactor",shadowAgronomy.waterStressFactor());
        risk=withAgronomyRisk(risks.evaluate(environment,30),agronomy.snapshot());
        shadowRisk=withAgronomyRisk(shadowRisks.evaluate(shadow,30),shadowAgronomy.snapshot());
        String agronomyRisk=agronomy.snapshot().path("riskLevel").asText("LOW");
        if("HIGH".equals(agronomyRisk)&&!"HIGH".equals(previousAgronomyRisk)&&!pending&&autoActuation){
            if(weather==null)decisionCalls=0;
            if(decisionCalls<3){decision.put("status","NEEDS_DECISION");record("AGRONOMY_ALERT","根区或病害适生条件持续异常，需要复核当前方案");}
        }
        previousAgronomyRisk=agronomyRisk;
        cropState=crop.advanceCalibrated(cropState,simulation(environment),30,linear,agronomy.waterStressFactor());
        shadowCrop=crop.advanceCalibrated(shadowCrop,simulation(shadow),30,linear,shadowAgronomy.waterStressFactor());
        double h=frame.path("correctedHeightCm").asDouble(),increment=Math.max(0,h-lastReferenceHeight);
        heightDelta+=increment*(growthFactor(environment)*agronomy.waterStressFactor()-growthFactor(frame.path("environment")));
        shadowHeight+=increment*(growthFactor(shadow)*shadowAgronomy.waterStressFactor()-growthFactor(frame.path("environment")));
        lastReferenceHeight=h;
        // Protect the following interval after accounting the duties that ran
        // during this one. This is a discrete guard, not a continuous thermostat.
        enforceRuntimeInterlocks();
        if(observeTick>=0&&tick>=observeTick&& !pending) {
            String status="LOW".equals(risk.path("riskLevel").asText())?"MITIGATED":"UNRESOLVED";
            boolean blocked="BLOCKED".equals(decision.path("status").asText());
            if(!blocked)decision.put("status",status);decision.set("feedback",feedback());
            record("FEEDBACK",blocked?"方案被约束拦截；当前反馈仅反映继续运行的模拟状态":status.equals("MITIGATED")?"当前环境与根区条件风险已降低，病斑仍需人工复查":"风险仍存在，需要观察或调整");
            observeTick=-1;
        }
        ObjectNode row=mapper.createObjectNode();row.put("at",at.toString());row.put("temperatureC",environment.path("temperatureC").asDouble());
        row.put("airHumidityPct",environment.path("airHumidityPct").asDouble());row.put("shadowTemperatureC",shadow.path("temperatureC").asDouble());
        row.put("shadowHumidityPct",shadow.path("airHumidityPct").asDouble());row.put("vpdKpa",risk.path("vpdKpa").asDouble());trends.add(row);
        row.put("soilMoistureVwcPct",agronomy.snapshot().path("soilMoistureVwcPct").asDouble());
        row.put("shadowSoilMoistureVwcPct",shadowAgronomy.snapshot().path("soilMoistureVwcPct").asDouble());
        row.put("canopyWetnessProxy",agronomy.snapshot().path("canopyWetnessProxy").asDouble());
        if(trends.size()>96)trends.remove(0);
        return snapshot();
    }

    private SimulationState simulation(JsonNode e) {
        double t=e.path("temperatureC").asDouble(),rh=e.path("airHumidityPct").asDouble();
        // Legacy relative moisture stays neutral. The VWC-derived factor is passed
        // separately to advanceCalibrated, so the incompatible percentages are never mixed.
        return new SimulationState(at,t,rh,60,e.path("co2Ppm").asDouble(),e.path("ppfd").asDouble(),6.5,Math.max(0,es(t)*(1-rh/100)),0,0,"SCENARIO");
    }
    private Map<String,Double> effectiveDuties(){
        Map<String,Double> values=new LinkedHashMap<>();for(String code:CODES)values.put(code,power(code));return values;
    }
    private ObjectNode withAgronomyRisk(ObjectNode climate,JsonNode root){
        ArrayNode alerts=(ArrayNode)climate.path("alerts");
        String condition=root.path("rootCondition").asText();
        if(!"SUITABLE".equals(condition)){
            ObjectNode row=alerts.addObject();boolean wet="WET".equals(condition);
            double duration=root.path(wet?"continuousWetMinutes":"continuousDryMinutes").asDouble();
            row.put("code",wet?"ROOT_WET":"ROOT_DRY");row.put("title",wet?"根区过湿暴露":"根区缺水暴露");
            row.put("level",duration>=120?"HIGH":"MEDIUM");row.put("value",root.path("soilMoistureVwcPct").asDouble());
            row.put("threshold",root.path(wet?"wetThresholdVwcPct":"dryThresholdVwcPct").asDouble());row.put("unit","%vol");
            row.put("durationMinutes",duration);row.put("advice",wet?"暂停滴灌并复核排水；人工检查根区，不以模型估计替代传感器":"复核实际根区含水；按方案分时滴灌后检查水量与排水");
            row.put("basis","未标定体积含水率阈值和水量平衡");row.put("origin","SIMULATED_ROOT_WATER_BALANCE");row.put("estimatedInput",true);
        }
        for(JsonNode d:root.path("diseaseConditions"))if(!"LOW".equals(d.path("level").asText())){
            ObjectNode row=alerts.addObject();row.put("code",d.path("code").asText());row.put("title",d.path("title").asText());
            row.put("level",d.path("level").asText());row.put("durationMinutes",d.path("continuousExposureMinutes").asDouble());
            row.put("advice",d.path("review").asText());row.put("basis",d.path("condition").asText()+"；未标定条件规则，不能诊断病害");
            row.put("origin","SIMULATED_CONDITION_RULE");row.put("estimatedInput",true);
        }
        String level=climate.path("riskLevel").asText();
        if("HIGH".equals(root.path("riskLevel").asText()))level="HIGH";
        else if("LOW".equals(level)&&"MEDIUM".equals(root.path("riskLevel").asText()))level="MEDIUM";
        climate.put("riskLevel",level);return climate;
    }
    private double growthFactor(JsonNode e) {
        double t=e.path("temperatureC").asDouble();
        return clamp((t<24?(t-6)/18:(42-t)/18),0,1);
    }
    private ObjectNode buildEnvironment(JsonNode base,boolean untreated,int minutes) {
        double bt=base.path("temperatureC").asDouble(),br=base.path("airHumidityPct").asDouble();
        double moisture=absolute(bt,br),td=untreated?shadowT:tDelta,wd=untreated?shadowWater:waterDelta,cd=untreated?shadowCo2:co2Delta;
        double dt=0,rhBoundary=br,lightScale=1,wind=0,rain=0,weight=0;
        if(weather!=null){
            int elapsed=tick-weather.path("startTick").asInt(),remaining=weather.path("endTick").asInt()-tick;
            weight=Math.min(1,Math.max(0,Math.min(elapsed+1,remaining)/2.0));
            dt=weather.path("temperatureOffsetC").asDouble()*weight;
            rhBoundary=br+(weather.path("humidityBoundaryPct").asDouble(br)-br)*weight;
            lightScale=1+(weather.path("lightMultiplier").asDouble(1)-1)*weight;
            wind=weather.path("windMps").asDouble()*weight;rain=weather.path("rainMmH").asDouble()*weight;
        }
        double vent=untreated?0:Math.max(power("VENTILATION"),Math.max(power("ROOF_VENT"),power("EXHAUST_FAN")));
        double exchange=AIR_EXCHANGE+vent*VENT_EXCHANGE,heat=untreated?0:power("HEATING")*HEAT_GAIN;
        double cool=untreated?0:power("COOLING_PAD")*Math.max(0,(100-Math.max(br,rhBoundary))/65)*COOL_GAIN;
        double baselinePpfd=base.path("lightRaw").asDouble()*1000*LUX_TO_PPFD;
        double ppfd=baselinePpfd*lightScale;
        if(!untreated)ppfd=ppfd*(1-SHADE_FRACTION*power("SHADE"))+LIGHT_GAIN*power("SUPPLEMENTAL_LIGHT");
        // Add only the light anomaly: the historical baseline already includes its original heat load.
        double solarHeat=SOLAR_GAIN*clamp((ppfd-baselinePpfd)/800,-1.5,1.5);
        if(minutes>0){
            td+=(dt-td)*exchange+heat-cool+solarHeat;
            double outsideWater=absolute(bt+dt,rhBoundary);
            // Ventilation removes water towards a labelled dry-air proxy; heating alone changes RH, not water mass.
            double moistureTarget=outsideWater-moisture-vent*DRY_AIR_PROXY;
            wd+=(moistureTarget-wd)*(WATER_EXCHANGE+vent*VENT_WATER_EXCHANGE)+(untreated?0:power("COOLING_PAD")*PAD_WATER_GAIN);
            cd+=(0-cd)*(CO2_EXCHANGE+vent*VENT_CO2_EXCHANGE)+(untreated?0:power("CO2_SUPPLY")*CO2_GAIN);
        }
        double t=clamp(bt+td,-5,50),ah=clamp(moisture+wd,0.2,absolute(t,100));
        double rh=clamp(ah*(273.15+t)/(2167*es(t))*100,10,100);
        ObjectNode e=(ObjectNode)base.deepCopy();e.put("temperatureC",t);e.put("airHumidityPct",rh);
        e.put("co2Ppm",clamp(base.path("co2Ppm").asDouble()+cd,250,1600));e.put("ppfd",clamp(ppfd,0,2200));
        e.put("lightRaw",Math.max(0,ppfd/(1000*LUX_TO_PPFD)));e.put("absoluteHumidityGm3",ah);
        e.put("windMps",wind);e.put("rainMmH",rain);e.put("origin","SIMULATED_M3_ANCHORED");
        e.put("simulatedBoundaryTemperatureC",bt+dt);e.put("simulatedBoundaryHumidityPct",rhBoundary);
        e.put("waterStressNeutral",false);e.put("rootWaterOrigin","SIMULATED_ROOT_WATER_BALANCE");
        ScenarioAgronomyModel rootModel=untreated?shadowAgronomy:agronomy;
        e.put("soilMoistureVwcPct",rootModel.snapshot().path("soilMoistureVwcPct").asDouble());
        e.put("rootWaterStressFactor",rootModel.waterStressFactor());
        e.put("outsideBoundaryMeasured",false);e.put("estimated",true);
        if(untreated){shadowT=td;shadowWater=wd;shadowCo2=cd;}else{tDelta=td;waterDelta=wd;co2Delta=cd;}
        return e;
    }
    private double power(String code){return health.getOrDefault(code,false)?duty.getOrDefault(code,0.0):0;}
    private void enforceRuntimeInterlocks(){
        double wind=weather==null?0:weather.path("windMps").asDouble();
        if(wind>=10){stopForProtection("VENTILATION","强风/风雨边界下关闭侧窗");stopForProtection("ROOF_VENT","强风/风雨边界下关闭屋窗");}
        if(power("EXHAUST_FAN")<=0)stopForProtection("COOLING_PAD","排风已停止或故障，湿帘互锁停止");
        if(environment!=null&&environment.path("temperatureC").asDouble()>=29)stopForProtection("HEATING","棚内达到29°C，离散高温保护停止加热");
        if(power("HEATING")>0&&power("COOLING_PAD")>0)stopForProtection("COOLING_PAD","加热/湿帘互锁");
        if(power("VENTILATION")>0||power("ROOF_VENT")>0||power("EXHAUST_FAN")>0)stopForProtection("CO2_SUPPLY","通风运行时停止CO₂施用");
    }
    private void stopForProtection(String code,String reason){
        if(duty.getOrDefault(code,0.0)<=0)return;
        duty.put(code,0.0);until.remove(code);version++;record("SAFETY_INTERLOCK",code+"："+reason+"；这是虚拟保护规则");
    }
    private static double es(double t){return 0.6108*Math.exp(17.27*t/(t+237.3));}
    private static double absolute(double t,double rh){return 2167*es(t)*rh/100/(273.15+t);}
    private static double clamp(double n,double min,double max){return Math.max(min,Math.min(max,n));}

    public synchronized void trigger(String type) {
        if(closed)throw new IllegalStateException("运行已结束");
        if(pending)throw new IllegalStateException("AI正在分析，请等待当前方案返回");
        if("FAULT".equals(type)){
            if(health.values().contains(false))throw new IllegalArgumentException("当前已有设备故障");
            String c=new String[]{"HEATING","EXHAUST_FAN","IRRIGATION"}[random.nextInt(3)];health.put(c,false);duty.put(c,0.0);version++;
            enforceRuntimeInterlocks();
            record("FAULT",c+" 模拟故障，需手动模拟恢复");decisionCalls=0;decision.put("status","NEEDS_DECISION");return;
        }
        if(!Arrays.asList(WEATHER).contains(type))throw new IllegalArgumentException("未知天气事件");
        if(weather!=null)throw new IllegalArgumentException("请先结束当前天气事件");
        if("NIGHT_HUMIDITY".equals(type)&&at.getHour()>=6&&at.getHour()<18)throw new IllegalArgumentException("夜间高湿仅在夜间触发");
        weather=mapper.createObjectNode();weather.put("id",runId+"-event-"+(++eventSerial));weather.put("type",type);
        weather.put("title",title(type));weather.put("startTick",tick);weather.put("startedAt",at.toString());
        double temp=0,hum=reference.path("environment").path("airHumidityPct").asDouble(),light=1,wind=0,rain=0;int length=4;
        if("STORM".equals(type)){temp=-range(3,6);hum=range(90,98);light=range(.2,.5);wind=range(6,12);rain=range(20,50);length=integer(4,12);}
        if("COLD_SNAP".equals(type)){temp=-range(6,10);length=integer(6,16);}
        if("HEAT_WAVE".equals(type)){temp=range(5,9);light=range(1,1.15);length=integer(8,20);}
        if("STRONG_WIND".equals(type)){temp=-range(1,3);wind=range(12,20);length=integer(4,8);}
        if("OVERCAST".equals(type)){hum=range(85,95);light=range(.15,.4);rain=range(2,8);length=integer(16,32);}
        if("NIGHT_HUMIDITY".equals(type)){hum=range(90,98);length=integer(8,16);}
        weather.put("temperatureOffsetC",temp);weather.put("humidityBoundaryPct",hum);weather.put("lightMultiplier",light);
        weather.put("windMps",wind);weather.put("rainMmH",rain);weather.put("endTick",tick+length);
        weather.put("endsAt",at.plusMinutes(length*30L).toString());weather.put("source","SIMULATED");version++;
        enforceRuntimeInterlocks();
        decisionCalls=0;decision.put("status","NEEDS_DECISION");observeTick=-1;
        environment=buildEnvironment(reference.path("environment"),false,0);
        shadow=buildEnvironment(reference.path("environment"),true,0);
        record("WEATHER_START",title(type)+" 发生，持续 "+(length*.5)+" 模拟小时");
    }
    public synchronized void endWeather(){
        if(weather!=null){record("WEATHER_END",weather.path("title").asText()+" 已结束；风险继续按环境判断");weather=null;version++;nextEvent=tick+integer(8,24);}
    }
    public synchronized void repair(String c){
        if(!health.containsKey(c))throw new IllegalArgumentException("未知设备");
        if(pending)throw new IllegalStateException("请等待当前分析完成");
        health.put(c,true);version++;record("REPAIR",c+" 已模拟恢复；这是演示操作，不是实际维修");
    }
    public synchronized void configure(boolean events,boolean actuation){
        if(pending)throw new IllegalStateException("请等待当前分析完成后更改配置");
        autoEvents=events;autoActuation=actuation;version++;
    }
    public synchronized void bindTask(String id){
        if(id==null||!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException("农情任务编号无效");
        if(Objects.equals(farmTaskId,id))return;
        if(pending)throw new IllegalStateException("请等待当前分析完成后更改关联任务");
        farmTaskId=id;version++;
    }
    public synchronized boolean needsDecision(){
        String s=decision.path("status").asText();
        return !closed&&!pending&&autoActuation&&decisionCalls<3&&("NEEDS_DECISION".equals(s)||"UNRESOLVED".equals(s));
    }
    public synchronized boolean pending(){return pending;}
    public synchronized void close(){closed=true;pending=false;version++;}
    public synchronized ObjectNode beginDecision(String question,boolean apply){
        if(closed)throw new IllegalStateException("运行已结束");
        if(pending)throw new IllegalStateException("当前有一个AI请求正在运行");
        if(decisionCalls>=3&&weather!=null)throw new IllegalStateException("本次事件已完成三次分析，请观察反馈或结束事件");
        JsonNode previousFeedback=decision.path("feedback").deepCopy();
        observeTick=-1;
        pending=true;decisionCalls++;decision=mapper.createObjectNode();
        if(previousFeedback.isObject())decision.set("feedback",previousFeedback);
        decision.put("status","ANALYZING");decision.put("requestId",UUID.randomUUID().toString());
        decision.put("question",question);decision.put("version",version);decision.put("applyRequested",apply);
        decision.put("startedAt",at.toString());record("AI_REQUEST","AI正在读取当前事件和环境");
        ObjectNode context=snapshot();context.set("referenceEnvironment",reference.path("environment").deepCopy());
        context.put("question",question);return context;
    }
    public synchronized void decisionFailed(String id,String reason){
        if(!id.equals(decision.path("requestId").asText())||closed)return;
        pending=false;decision.put("status","FAILED");decision.put("error",reason);record("AI_FAILED",reason);
    }
    public synchronized ObjectNode applyPlan(JsonNode plan,long expectedVersion,String requestId,boolean apply) {
        if(requestIds.contains(requestId))return snapshot();
        if(closed||expectedVersion!=version||!requestId.equals(decision.path("requestId").asText())){
            if(requestId.equals(decision.path("requestId").asText())){pending=false;decision.put("status","STALE");record("AI_STALE","状态已变化，方案未应用");}
            return snapshot();
        }
        requestIds.add(requestId);while(requestIds.size()>128)requestIds.remove(requestIds.iterator().next());
        pending=false;decision.set("plan",plan.deepCopy());decision.put("provider","DeepSeek");
        Map<String,Double> target=new LinkedHashMap<>(duty);Set<String> seen=new HashSet<>();
        List<String> errors=new ArrayList<>();JsonNode actions=plan.path("actions");
        if(!actions.isArray()||actions.size()>10)errors.add("动作须为数组且最多10条");
        for(JsonNode a:actions){
            String c=a.path("device").asText();JsonNode raw=a.get("duty");
            if(!target.containsKey(c)){errors.add("未知设备 "+c);continue;}
            if(!seen.add(c)){errors.add("设备重复 "+c);continue;}
            if(raw==null||!raw.isNumber()||!Double.isFinite(raw.asDouble())||raw.asDouble()<0||raw.asDouble()>1){errors.add(c+" 出力须为0至1");continue;}
            if(raw.asDouble()>0&&!health.get(c)){errors.add(c+" 当前故障");continue;}
            JsonNode durationNode=a.get("durationSteps");
            if(durationNode!=null&&!durationNode.isIntegralNumber()){errors.add(c+" 持续步数须为整数");continue;}
            int duration=a.path("durationSteps").asInt(4);
            if(duration<2||duration>24){errors.add(c+" 持续步数须为2至24");continue;}
            target.put(c,raw.asDouble());
        }
        double wind=weather==null?0:weather.path("windMps").asDouble();
        if(wind>=10&&(target.get("VENTILATION")>0||target.get("ROOF_VENT")>0))errors.add("模拟强风/风雨约束：屋窗与侧窗应关闭");
        if(target.get("CO2_SUPPLY")>0&&(target.get("VENTILATION")>0||target.get("ROOF_VENT")>0||target.get("EXHAUST_FAN")>0))errors.add("CO₂施用与通风互锁");
        if(target.get("COOLING_PAD")>0&&target.get("EXHAUST_FAN")<=0)errors.add("湿帘需要排风");
        if(target.get("HEATING")>0&&target.get("COOLING_PAD")>0)errors.add("加热与湿帘不能同时运行");
        if(target.get("HEATING")>0&&environment.path("temperatureC").asDouble()>=29)errors.add("项目高温保护：棚内温度达到29°C时不能开启加热");
        decision.set("constraints",mapper.valueToTree(errors));
        if(!errors.isEmpty()) {decision.put("status","BLOCKED");record("ACTION_BLOCKED",String.join("；",errors));observeTick=tick+2;}
        else if(!apply){decision.put("status","PROPOSED");record("AI_PROPOSAL","方案已生成，未执行设备动作");}
        else {
            for(JsonNode a:actions){String c=a.path("device").asText();duty.put(c,a.path("duty").asDouble());until.put(c,tick+a.path("durationSteps").asInt(4));}
            version++;decision.put("status","OBSERVING");decision.put("executedAt",at.toString());
            decision.set("executedDevices",mapper.valueToTree(duty));observeTick=tick+2;
            record("ACTION_APPLIED","已应用仿真设备方案，至少观察两个半小时时段");
        }
        return snapshot();
    }
    private ObjectNode feedback(){
        ObjectNode f=mapper.createObjectNode();f.put("at",at.toString());f.set("environment",environment.deepCopy());f.set("risk",risk.deepCopy());
        f.set("withoutIntervention",shadow.deepCopy());f.put("temperatureDifferenceC",environment.path("temperatureC").asDouble()-shadow.path("temperatureC").asDouble());
        f.put("humidityDifferencePct",environment.path("airHumidityPct").asDouble()-shadow.path("airHumidityPct").asDouble());
        f.set("agronomy",agronomy.snapshot());f.set("shadowAgronomy",shadowAgronomy.snapshot());f.set("resources",agronomy.resources());
        f.set("effects",effects());f.put("soilMoistureDifferenceVwcPct",agronomy.snapshot().path("soilMoistureVwcPct").asDouble()-shadowAgronomy.snapshot().path("soilMoistureVwcPct").asDouble());
        f.put("applicationStatus",decision.has("executedAt")?"APPLIED_TO_SIMULATION":decision.path("status").asText());
        f.put("source","MODEL_COMPARISON");f.put("fieldValidated",false);return f;
    }
    private ArrayNode parameterRegistry(){
        ArrayNode p=mapper.createArrayNode();
        parameter(p,"heatingGain",HEAT_GAIN,"°C/30min·满出力");parameter(p,"coolingGain",COOL_GAIN,"°C/30min·干空气代理");
        parameter(p,"solarAnomalyGain",SOLAR_GAIN,"°C/30min·800PPFD差值");parameter(p,"airExchange",AIR_EXCHANGE,"比例/30min");
        parameter(p,"ventilationExchange",VENT_EXCHANGE,"附加比例/30min·满出力");parameter(p,"waterExchange",WATER_EXCHANGE,"比例/30min");
        parameter(p,"ventilationWaterExchange",VENT_WATER_EXCHANGE,"附加比例/30min·满出力");parameter(p,"dryAirProxy",DRY_AIR_PROXY,"g/m³");
        parameter(p,"padWaterGain",PAD_WATER_GAIN,"g/m³/30min·满出力");parameter(p,"co2Gain",CO2_GAIN,"ppm/30min·满出力");
        parameter(p,"co2Exchange",CO2_EXCHANGE,"比例/30min");parameter(p,"ventilationCo2Exchange",VENT_CO2_EXCHANGE,"附加比例/30min·满出力");
        parameter(p,"shadeFraction",SHADE_FRACTION,"遮光比例·满出力");parameter(p,"supplementalLight",LIGHT_GAIN,"µmol/m²/s·满出力");
        parameter(p,"heatingSafetyCutoff",29,"°C离散保护阈值");parameter(p,"windowWindCutoff",10,"m/s模拟边界保护阈值");
        parameter(p,"actionStepMinutes",30,"min模拟动作步长");parameter(p,"actionMinimumSteps",2,"步");parameter(p,"actionMaximumSteps",24,"步");
        parameter(p,"luxToPpfd",LUX_TO_PPFD,"µmol/m²/s per lux");agronomy.appendParameters(p);return p;
    }
    private void parameter(ArrayNode list,String name,double value,String unit){ObjectNode p=list.addObject();p.put("name",name);p.put("value",value);p.put("unit",unit);p.put("source","PROJECT_ENGINEERING_ASSUMPTION");p.put("calibrated",false);p.put("scope","虚拟气候响应/保护参数；未由M3或实际设备标定");}
    private ArrayNode effects(){
        ArrayNode list=mapper.createArrayNode();JsonNode a=agronomy.snapshot(),s=shadowAgronomy.snapshot();
        double dt=environment.path("temperatureC").asDouble()-shadow.path("temperatureC").asDouble();
        double drh=environment.path("airHumidityPct").asDouble()-shadow.path("airHumidityPct").asDouble();
        double root=a.path("soilMoistureVwcPct").asDouble()-s.path("soilMoistureVwcPct").asDouble();
        double wet=a.path("canopyWetnessProxy").asDouble()-s.path("canopyWetnessProxy").asDouble();
        String climate=String.format(Locale.ROOT,"组合方案相较未干预分支：温度%+.1f°C，湿度%+.1f个百分点",dt,drh);
        for(String code:CODES){
            String title,mechanism,observed=climate,tradeoff,review;
            switch(code){
                case "IRRIGATION":
                    title="分时滴灌";mechanism="流量×出力×时间补入根区；存水按蒸散与排水扣减，水分胁迫参与虚拟生长";
                    observed=String.format(Locale.ROOT,"根区体积含水率相较对照%+.2f个百分点；累计滴灌%.1fL；本段%.1fL",root,a.path("waterUsedL").asDouble(),agronomy.resources().path("lastStep").path("irrigationWaterL").asDouble());
                    tradeoff="灌水过量会增加排水与过湿暴露；雨量不自动当作封闭棚内根区灌水";review="30–60分钟后复核根区含水和排水；现场应查看传感器、滴头及土壤";break;
                case "CIRCULATION_FAN":
                    title="冠层环流";mechanism="提高湿润代理的干燥速率，表示冠层混合；不生成空间风速场，也不改变棚均湿度";
                    observed=String.format(Locale.ROOT,"冠层湿润代理相较对照%+.3f（0–1）；湿润累计%.0f分钟",wet,a.path("canopyWetMinutes").asDouble());
                    tradeoff="增加耗电；不能替代换气排湿，实际叶面是否干燥仍需检查";review="30–60分钟后看代理趋势，并人工检查叶面与空气流通";break;
                case "HEATING":
                    title="加热保温";mechanism="增加模拟空气温度；相对湿度随温度变化，加热自身不移除水汽";
                    tradeoff="增加耗电；仅降低相对湿度时，水汽量可能仍高";review="30–60分钟后核对温度、VPD和绝对湿度，避免过热";break;
                case "COOLING_PAD":
                    title="湿帘降温";mechanism="按进气湿度代理降低温度并加入水汽；须配合排风";
                    tradeoff="耗水耗电并提高水汽量；阴雨高湿时降温能力下降";review="30–60分钟后复核温度及湿度，防止降温后仍持续高湿";break;
                case "VENTILATION":case "ROOF_VENT":case "EXHAUST_FAN":
                    title="VENTILATION".equals(code)?"侧窗换气":"ROOF_VENT".equals(code)?"屋窗换气":"排风换气";
                    mechanism="向模拟边界交换热量、水汽和CO₂；根据边界条件改变温湿度";
                    tradeoff="可能散失热量和CO₂；强风时开窗受互锁限制，排风需耗电";review="30–60分钟后复核湿度、温度与风雨条件；通风可能升温或降温";break;
                case "SUPPLEMENTAL_LIGHT":
                    title="补光";mechanism="增加PPFD，参与光合干物质增量，同时加入辐射热差";
                    observed=String.format(Locale.ROOT,"组合方案相较对照：PPFD%+.0fµmol/m²/s；虚拟总干重%+.3fg/m²",environment.path("ppfd").asDouble()-shadow.path("ppfd").asDouble(),cropState.getWTotal()-shadowCrop.getWTotal());
                    tradeoff="增加耗电及热负荷；光强与生长响应均未标定";review="即时复核光照和温度，生长变化应按天观察，不据短时动画判断产量";break;
                case "SHADE":
                    title="遮阳";mechanism="降低PPFD与辐射热差，光合输入随之减少";
                    observed=String.format(Locale.ROOT,"组合方案相较对照：PPFD%+.0fµmol/m²/s，温度%+.1f°C",environment.path("ppfd").asDouble()-shadow.path("ppfd").asDouble(),dt);
                    tradeoff="可减少光合输入；阴雨低光条件下不宜持续遮光";review="30–60分钟后核对温度与PPFD，光照不足时重新评估遮阳";break;
                default:
                    title="CO₂补充";mechanism="按出力提高模拟CO₂浓度并参与作物同化因子；与通风互锁";
                    observed=String.format(Locale.ROOT,"组合方案相较对照：CO₂%+.0fppm；累计CO₂代理用量%.3fkg",environment.path("co2Ppm").asDouble()-shadow.path("co2Ppm").asDouble(),agronomy.resources().path("co2Kg").asDouble());
                    tradeoff="消耗CO₂资源；缺光或环境胁迫时增益有限，不能与通风同时应用";review="复核CO₂与PPFD；生长增益需长期观测，浓度响应不等于实测产量";break;
            }
            String status=power(code)>0?"APPLIED":"IDLE";
            if(!health.getOrDefault(code,true))status="FAULT";
            else if(power(code)==0)for(JsonNode action:decision.path("plan").path("actions"))if(code.equals(action.path("device").asText())){
                if(action.path("duty").asDouble()==0)status=decision.has("executedAt")?"APPLIED_STOP":decision.path("status").asText("PROPOSED");
                else status=decision.has("executedAt")?"EXPIRED":decision.path("status").asText("PROPOSED");
            }
            ObjectNode e=list.addObject();e.put("device",code);e.put("title",title);e.put("mechanism",mechanism);
            e.put("observed",observed);e.put("tradeoff",tradeoff);e.put("review",review);e.put("status",status);
            e.put("duty",power(code));e.put("origin","SIMULATED_RESPONSE");e.put("fieldValidated",false);
        }
        if(!"LOW".equals(a.path("diseaseConditionLevel").asText())){
            ObjectNode e=list.addObject();e.put("device","HUMAN_INSPECTION");e.put("title","复查病斑与叶面");
            e.put("mechanism","环境管理降低后续适生条件暴露；已有病斑、病原和防治处置需要额外证据");
            e.put("observed","当前仅计算病害适生条件；未计算病斑面积、病原量或治愈率");
            e.put("tradeoff","环境改善不能自动确认病害治愈或替代人工防治");e.put("review","补拍叶片、记录病斑扩展与人工处置；完成后再登记复查结果");
            e.put("status","MANUAL_REQUIRED");e.put("origin","REQUIRES_HUMAN_OBSERVATION");e.put("fieldValidated",false);
        }
        return list;
    }
    public synchronized ObjectNode snapshot(){
        ObjectNode o=mapper.createObjectNode();o.put("runId",runId);o.put("version",version);o.put("seed",seed);o.put("tick",tick);o.put("at",at.toString());
        o.put("source","SIMULATED");o.put("autoEvents",autoEvents);o.put("autoActuation",autoActuation);o.put("pending",pending);
        if(farmTaskId!=null)o.put("farmTaskId",farmTaskId);
        o.set("environment",environment.deepCopy());o.set("withoutIntervention",shadow.deepCopy());o.set("risk",risk.deepCopy());o.set("shadowRisk",shadowRisk.deepCopy());
        o.set("weather",weather==null?NullNode.instance:weather.deepCopy());o.set("decision",decision.deepCopy());
        o.put("responseModelVersion",RESPONSE_VERSION);o.set("parameters",parameterRegistry());
        o.set("agronomy",agronomy.snapshot());o.set("shadowAgronomy",shadowAgronomy.snapshot());
        o.set("resources",agronomy.resources());o.set("effects",effects());
        o.put("actionEffects","滴灌按出力与时间加入根区水量；蒸散和排水减少存水，缺水/过湿影响虚拟生长；环流降低冠层湿润代理但不改变棚均温湿度；遮阳/补光影响PPFD及辐射热差；加热/湿帘影响温度和水汽；通风/排风与模拟边界交换温度、水汽及CO₂，效果依边界条件而变。资源来自同一设备时段计量，全部为未标定仿真。");
        ObjectNode switches=mapper.createObjectNode();for(String c:CODES)switches.put(c,power(c)>0);
        o.set("devices",switches);o.set("deviceDuty",mapper.valueToTree(duty));o.set("deviceHealth",mapper.valueToTree(health));
        ObjectNode g=mapper.createObjectNode();g.put("plantHeightCm",Math.max(0,lastReferenceHeight+heightDelta));g.put("referenceHeightCm",lastReferenceHeight);
        g.put("withoutInterventionHeightCm",Math.max(0,lastReferenceHeight+shadowHeight));g.put("lai",cropState.getLai());g.put("wTotal",cropState.getWTotal());
        g.put("wFruit",cropState.getWFruit());g.put("stage",cropState.getStage().name());g.put("fieldValidated",false);o.set("growth",g);
        g.put("rootWaterStressFactor",agronomy.waterStressFactor());g.put("shadowRootWaterStressFactor",shadowAgronomy.waterStressFactor());
        g.put("withoutInterventionWTotal",shadowCrop.getWTotal());g.put("withoutInterventionWFruit",shadowCrop.getWFruit());
        g.put("origin","SIMULATED_RESPONSE_ANCHORED_TO_ARRIVED_REFERENCE");
        o.set("timeline",mapper.valueToTree(log.subList(Math.max(0,log.size()-80),log.size())));o.set("trends",mapper.valueToTree(trends));
        o.set("assumptions",mapper.valueToTree(Arrays.asList("事件、风雨、设备和室外边界为模拟，M3为棚内历史参考。",
            "换气、热量及水汽出力是未校准工程代理；通风干空气代理不来自室外测量。",
            "株高扰动为温度适宜度与根区水分胁迫代理；生物量沿用未校准RUE。对照不是现场因果验证。",
            "根区体积含水率、有效储水层厚度、排水与设备容量均为场景假设，不使用旧相对含水率冒充实测。",
            "病害仅显示适生条件与湿润暴露，未模拟病原量或病斑治愈；人工处置须登记并复查。",
            "环流仅影响无量纲冠层湿润代理；lux/PPFD换算仍有假设。")));
        return o;
    }
    /** Stores only arrived state, including bounded histories and explicit RNG state. */
    public synchronized ObjectNode checkpoint(){
        ObjectNode o=mapper.createObjectNode();o.put("checkpointVersion",1);o.put("responseModelVersion",RESPONSE_VERSION);
        o.put("runId",runId);o.put("seed",seed);o.put("randomState",random.state());o.put("linear",linear);
        o.put("at",at.toString());o.put("version",version);o.put("tick",tick);o.put("nextEvent",nextEvent);
        o.put("eventSerial",eventSerial);o.put("decisionCalls",decisionCalls);o.put("observeTick",observeTick);
        o.put("autoEvents",autoEvents);o.put("autoActuation",autoActuation);o.put("pending",pending);o.put("closed",closed);
        o.put("previousAgronomyRisk",previousAgronomyRisk);
        if(farmTaskId!=null)o.put("farmTaskId",farmTaskId);
        o.put("tDelta",tDelta);o.put("waterDelta",waterDelta);o.put("co2Delta",co2Delta);
        o.put("shadowT",shadowT);o.put("shadowWater",shadowWater);o.put("shadowCo2",shadowCo2);
        o.put("heightDelta",heightDelta);o.put("shadowHeight",shadowHeight);o.put("lastReferenceHeight",lastReferenceHeight);
        o.set("reference",reference.deepCopy());o.set("environment",environment.deepCopy());o.set("shadow",shadow.deepCopy());
        o.set("weather",weather==null?NullNode.instance:weather.deepCopy());o.set("decision",decision.deepCopy());
        o.set("risk",risk.deepCopy());o.set("shadowRisk",shadowRisk.deepCopy());
        o.set("riskState",risks.checkpoint());o.set("shadowRiskState",shadowRisks.checkpoint());
        o.set("crop",cropCheckpoint(cropState));o.set("shadowCrop",cropCheckpoint(shadowCrop));
        o.set("agronomy",agronomy.checkpoint());o.set("shadowAgronomy",shadowAgronomy.checkpoint());
        o.set("deviceDuty",mapper.valueToTree(duty));o.set("deviceHealth",mapper.valueToTree(health));o.set("deviceUntil",mapper.valueToTree(until));
        o.set("requestIds",mapper.valueToTree(requestIds));o.set("log",mapper.valueToTree(log));o.set("trends",mapper.valueToTree(trends));
        return o;
    }
    public synchronized void restoreCheckpoint(JsonNode o){
        if(o.path("checkpointVersion").asInt()!=1||!RESPONSE_VERSION.equals(o.path("responseModelVersion").asText()))throw new IllegalArgumentException("大棚响应存档版本不匹配");
        if(!runId.equals(o.path("runId").asText())||seed!=o.path("seed").asLong()||Double.compare(linear,requiredNumber(o,"linear"))!=0)throw new IllegalArgumentException("大棚存档运行或参数不匹配");
        JsonNode ids=o.path("requestIds"),logs=o.path("log"),rows=o.path("trends");
        if(!ids.isArray()||ids.size()>128||!logs.isArray()||logs.size()>400||!rows.isArray()||rows.size()>96)throw new IllegalArgumentException("大棚存档历史结构无效");
        long savedVersion=o.path("version").asLong(-1);int savedTick=o.path("tick").asInt(-1);
        if(savedVersion<0||savedTick<0)throw new IllegalArgumentException("大棚存档游标无效");
        random.restoreState(o.path("randomState").asLong(-1));
        cropState=restoreCrop(o.path("crop"));shadowCrop=restoreCrop(o.path("shadowCrop"));
        agronomy.restoreCheckpoint(o.path("agronomy"));shadowAgronomy.restoreCheckpoint(o.path("shadowAgronomy"));
        risks.restoreCheckpoint(o.path("riskState"));shadowRisks.restoreCheckpoint(o.path("shadowRiskState"));
        reference=requiredObject(o,"reference");environment=requiredObject(o,"environment");shadow=requiredObject(o,"shadow");
        decision=requiredObject(o,"decision");risk=requiredObject(o,"risk");shadowRisk=requiredObject(o,"shadowRisk");
        weather=o.path("weather").isNull()?null:requiredObject(o,"weather");
        at=LocalDateTime.parse(o.path("at").asText());version=savedVersion;tick=savedTick;
        nextEvent=o.path("nextEvent").asInt();eventSerial=o.path("eventSerial").asInt();decisionCalls=o.path("decisionCalls").asInt();observeTick=o.path("observeTick").asInt(-1);
        if(nextEvent<0||eventSerial<0||decisionCalls<0)throw new IllegalArgumentException("大棚存档事件状态无效");
        autoEvents=o.path("autoEvents").asBoolean();autoActuation=o.path("autoActuation").asBoolean();closed=o.path("closed").asBoolean();
        previousAgronomyRisk=o.path("previousAgronomyRisk").asText("LOW");
        farmTaskId=o.hasNonNull("farmTaskId")?o.path("farmTaskId").asText():null;
        if(farmTaskId!=null&&!UUID.fromString(farmTaskId).toString().equals(farmTaskId))throw new IllegalArgumentException("存档农情任务编号无效");
        tDelta=requiredNumber(o,"tDelta");waterDelta=requiredNumber(o,"waterDelta");co2Delta=requiredNumber(o,"co2Delta");
        shadowT=requiredNumber(o,"shadowT");shadowWater=requiredNumber(o,"shadowWater");shadowCo2=requiredNumber(o,"shadowCo2");
        heightDelta=requiredNumber(o,"heightDelta");shadowHeight=requiredNumber(o,"shadowHeight");lastReferenceHeight=requiredNumber(o,"lastReferenceHeight");
        duty.clear();health.clear();until.clear();
        for(String c:CODES){
            double d=requiredNumber(o.path("deviceDuty"),c);
            if(d<0||d>1||!o.path("deviceHealth").path(c).isBoolean())throw new IllegalArgumentException("大棚存档设备状态无效");
            duty.put(c,d);health.put(c,o.path("deviceHealth").path(c).asBoolean());
            JsonNode expiration=o.path("deviceUntil").get(c);
            if(expiration!=null){if(!expiration.isIntegralNumber()||expiration.asInt()<0)throw new IllegalArgumentException("大棚存档动作时限无效");until.put(c,expiration.asInt());}
        }
        requestIds.clear();for(JsonNode id:ids){if(!id.isTextual()||id.asText().length()>128)throw new IllegalArgumentException("大棚存档请求编号无效");requestIds.add(id.asText());}
        log.clear();for(JsonNode item:logs){if(!item.isObject())throw new IllegalArgumentException("大棚存档过程记录无效");log.add((ObjectNode)item.deepCopy());}
        trends.clear();for(JsonNode item:rows){if(!item.isObject())throw new IllegalArgumentException("大棚存档趋势记录无效");trends.add((ObjectNode)item.deepCopy());}
        pending=false;
        if(o.path("pending").asBoolean()||"ANALYZING".equals(decision.path("status").asText())){
            decision.put("status","INTERRUPTED");decision.put("error","服务重启中断了在途AI请求；请重新分析，原请求不会自动执行");
            observeTick=-1;version++;record("AI_INTERRUPTED","在途AI请求已中断，恢复后需人工重新发起");
        }
    }
    private ObjectNode cropCheckpoint(TomatoCropState c){
        ObjectNode o=mapper.createObjectNode();o.put("gdd",c.getGdd());o.put("lai",c.getLai());o.put("heightCm",c.getPlantHeightCm());
        o.put("leafG",c.getWLeaf());o.put("stemG",c.getWStem());o.put("rootG",c.getWRoot());o.put("fruitG",c.getWFruit());o.put("totalG",c.getWTotal());
        o.put("fruitSetRate",c.getFruitSetRate());o.put("fruitCount",c.getFruitCount());o.put("singleFruitWeightG",c.getSingleFruitWeightG());
        o.put("temperatureFactor",c.getTemperatureFactor());o.put("co2Factor",c.getCo2Factor());o.put("waterFactor",c.getWaterFactor());
        o.put("stage",c.getStage().name());o.put("mature",c.isMature());return o;
    }
    private TomatoCropState restoreCrop(JsonNode o){
        return new TomatoCropState(requiredNumber(o,"gdd"),requiredNumber(o,"lai"),requiredNumber(o,"heightCm"),
            requiredNumber(o,"leafG"),requiredNumber(o,"stemG"),requiredNumber(o,"rootG"),requiredNumber(o,"fruitG"),requiredNumber(o,"totalG"),
            requiredNumber(o,"fruitSetRate"),o.path("fruitCount").asInt(),requiredNumber(o,"singleFruitWeightG"),
            requiredNumber(o,"temperatureFactor"),requiredNumber(o,"co2Factor"),requiredNumber(o,"waterFactor"),
            CropStage.valueOf(o.path("stage").asText()),o.path("mature").asBoolean());
    }
    private ObjectNode requiredObject(JsonNode o,String key){
        if(!o.path(key).isObject())throw new IllegalArgumentException("大棚存档对象无效: "+key);
        return (ObjectNode)o.path(key).deepCopy();
    }
    private static double requiredNumber(JsonNode o,String key){
        JsonNode n=o.get(key);if(n==null||!n.isNumber()||!Double.isFinite(n.asDouble()))throw new IllegalArgumentException("大棚存档数值无效: "+key);return n.asDouble();
    }
    /** Java Random's documented 48-bit sequence with state owned by this class. */
    private static final class CheckpointRandom extends Random {
        private static final long MULTIPLIER=0x5DEECE66DL,ADDEND=0xBL,MASK=(1L<<48)-1;
        private long generatorState;
        CheckpointRandom(long seed){super(0L);generatorState=(seed^MULTIPLIER)&MASK;}
        @Override protected int next(int bits){generatorState=(generatorState*MULTIPLIER+ADDEND)&MASK;return (int)(generatorState>>>(48-bits));}
        long state(){return generatorState;}
        void restoreState(long saved){if(saved<0||saved>MASK)throw new IllegalArgumentException("随机过程存档无效");generatorState=saved;}
    }
    private void record(String type,String text){ObjectNode e=mapper.createObjectNode();e.put("type",type);e.put("message",text);e.put("at",at.toString());e.put("tick",tick);e.put("source","SIMULATED");log.add(e);if(log.size()>400)log.remove(0);}
    private double range(double min,double max){return min+random.nextDouble()*(max-min);}
    private int integer(int min,int max){return min+random.nextInt(max-min+1);}
    private String title(String type){switch(type){case "STORM":return "暴雨";case "COLD_SNAP":return "骤降温";case "HEAT_WAVE":return "热浪";case "STRONG_WIND":return "强风";case "OVERCAST":return "连续阴雨";default:return "夜间高湿";}}
}
