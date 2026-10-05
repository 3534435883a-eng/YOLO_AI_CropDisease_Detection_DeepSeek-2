package com.example.Ece.agent.m3.scenario;

import com.example.Ece.agent.crop.TomatoCropGrowthModel;
import com.example.Ece.agent.crop.TomatoCropState;
import com.example.Ece.agent.model.SimulationState;
import com.example.Ece.agent.m3.M3LiveRiskEngine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import java.time.LocalDateTime;
import java.util.*;

/** A counterfactual greenhouse, anchored to indoor M3 changes, never labelled as measured weather. */
public final class ScenarioSession {
    private static final String[] CODES={"HEATING","IRRIGATION","VENTILATION","SUPPLEMENTAL_LIGHT","SHADE","CO2_SUPPLY","ROOF_VENT","EXHAUST_FAN","COOLING_PAD","CIRCULATION_FAN"};
    private static final String[] WEATHER={"STORM","COLD_SNAP","HEAT_WAVE","STRONG_WIND","OVERCAST","NIGHT_HUMIDITY"};
    private static final double HEAT_GAIN=1.8, COOL_GAIN=2.0, SOLAR_GAIN=1.2,
        AIR_EXCHANGE=.14, VENT_EXCHANGE=.35, WATER_EXCHANGE=.12, VENT_WATER_EXCHANGE=.32,
        DRY_AIR_PROXY=1.6, PAD_WATER_GAIN=.8, CO2_GAIN=100, CO2_EXCHANGE=.12,
        VENT_CO2_EXCHANGE=.5, SHADE_FRACTION=.55, LIGHT_GAIN=150, LUX_TO_PPFD=.0185;
    private final ObjectMapper mapper;
    private final Random random;
    private final long seed;
    private final String runId;
    private final TomatoCropGrowthModel crop;
    private final double linear;
    private TomatoCropState cropState,shadowCrop;
    private final M3LiveRiskEngine risks,shadowRisks;
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

    public ScenarioSession(String runId,long seed,ObjectMapper mapper,TomatoCropGrowthModel crop,
                           TomatoCropState initial,double linear,JsonNode initialFrame) {
        this.runId=runId;this.seed=seed;this.mapper=mapper;this.random=new Random(seed);this.crop=crop;
        this.cropState=initial;this.shadowCrop=initial;this.linear=linear;
        risks=new M3LiveRiskEngine(mapper);shadowRisks=new M3LiveRiskEngine(mapper);
        for(String c:CODES){duty.put(c,0.0);health.put(c,true);}
        reference=(ObjectNode)initialFrame.deepCopy();at=LocalDateTime.parse(reference.path("at").asText());
        lastReferenceHeight=reference.path("correctedHeightCm").asDouble();
        environment=buildEnvironment(reference.path("environment"),false,0);shadow=environment.deepCopy();
        risk=risks.evaluate(environment,0);shadowRisk=shadowRisks.evaluate(shadow,0);
        decision=mapper.createObjectNode();decision.put("status","IDLE");
    }

    public synchronized ObjectNode advance(JsonNode frame) {
        reference=(ObjectNode)frame.deepCopy();at=LocalDateTime.parse(frame.path("at").asText());tick++;version++;
        for(String c:CODES)if(until.getOrDefault(c,Integer.MAX_VALUE)<=tick){duty.put(c,0.0);until.remove(c);record("ACTION_EXPIRED",c+" 仿真动作有效期结束");}
        if(weather!=null&&tick>=weather.path("endTick").asInt())endWeather();
        if(autoEvents&&weather==null&&tick>=nextEvent) {
            String type=WEATHER[random.nextInt(WEATHER.length)];
            if("NIGHT_HUMIDITY".equals(type)&&at.getHour()>=6&&at.getHour()<18)type="OVERCAST";
            trigger(type);
            if(random.nextDouble()<0.1&&health.values().stream().allMatch(Boolean::booleanValue))trigger("FAULT");
        }
        environment=buildEnvironment(frame.path("environment"),false,30);
        shadow=buildEnvironment(frame.path("environment"),true,30);
        risk=risks.evaluate(environment,30);shadowRisk=shadowRisks.evaluate(shadow,30);
        cropState=crop.advanceCalibrated(cropState,simulation(environment),30,linear);
        shadowCrop=crop.advanceCalibrated(shadowCrop,simulation(shadow),30,linear);
        double h=frame.path("correctedHeightCm").asDouble(),increment=Math.max(0,h-lastReferenceHeight);
        heightDelta+=increment*(growthFactor(environment)-growthFactor(frame.path("environment")));
        shadowHeight+=increment*(growthFactor(shadow)-growthFactor(frame.path("environment")));
        lastReferenceHeight=h;
        if(observeTick>=0&&tick>=observeTick&& !pending) {
            String status="LOW".equals(risk.path("riskLevel").asText())?"MITIGATED":"UNRESOLVED";
            decision.put("status",status);decision.set("feedback",feedback());
            record("FEEDBACK",status.equals("MITIGATED")?"环境风险已缓解，继续观察":"风险仍存在，需要观察或调整");
            observeTick=-1;
        }
        ObjectNode row=mapper.createObjectNode();row.put("at",at.toString());row.put("temperatureC",environment.path("temperatureC").asDouble());
        row.put("airHumidityPct",environment.path("airHumidityPct").asDouble());row.put("shadowTemperatureC",shadow.path("temperatureC").asDouble());
        row.put("shadowHumidityPct",shadow.path("airHumidityPct").asDouble());row.put("vpdKpa",risk.path("vpdKpa").asDouble());trends.add(row);
        if(trends.size()>96)trends.remove(0);
        return snapshot();
    }

    private SimulationState simulation(JsonNode e) {
        double t=e.path("temperatureC").asDouble(),rh=e.path("airHumidityPct").asDouble();
        return new SimulationState(at,t,rh,60,e.path("co2Ppm").asDouble(),e.path("ppfd").asDouble(),6.5,Math.max(0,es(t)*(1-rh/100)),0,0,"SCENARIO");
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
        e.put("waterStressNeutral",true);e.put("outsideBoundaryMeasured",false);e.put("estimated",true);
        if(untreated){shadowT=td;shadowWater=wd;shadowCo2=cd;}else{tDelta=td;waterDelta=wd;co2Delta=cd;}
        return e;
    }
    private double power(String code){return health.getOrDefault(code,false)?duty.getOrDefault(code,0.0):0;}
    private static double es(double t){return 0.6108*Math.exp(17.27*t/(t+237.3));}
    private static double absolute(double t,double rh){return 2167*es(t)*rh/100/(273.15+t);}
    private static double clamp(double n,double min,double max){return Math.max(min,Math.min(max,n));}

    public synchronized void trigger(String type) {
        if(closed)throw new IllegalStateException("运行已结束");
        if(pending)throw new IllegalStateException("AI正在分析，请等待当前方案返回");
        if("FAULT".equals(type)){
            if(health.values().contains(false))throw new IllegalArgumentException("当前已有设备故障");
            String c=new String[]{"HEATING","EXHAUST_FAN","IRRIGATION"}[random.nextInt(3)];health.put(c,false);duty.put(c,0.0);version++;
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
        f.put("humidityDifferencePct",environment.path("airHumidityPct").asDouble()-shadow.path("airHumidityPct").asDouble());f.put("source","MODEL_COMPARISON");return f;
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
        parameter(p,"luxToPpfd",LUX_TO_PPFD,"µmol/m²/s per lux");return p;
    }
    private void parameter(ArrayNode list,String name,double value,String unit){ObjectNode p=list.addObject();p.put("name",name);p.put("value",value);p.put("unit",unit);p.put("source","PROJECT_ENGINEERING_ASSUMPTION");p.put("calibrated",false);}
    public synchronized ObjectNode snapshot(){
        ObjectNode o=mapper.createObjectNode();o.put("runId",runId);o.put("version",version);o.put("seed",seed);o.put("tick",tick);o.put("at",at.toString());
        o.put("source","SIMULATED");o.put("autoEvents",autoEvents);o.put("autoActuation",autoActuation);o.put("pending",pending);
        o.set("environment",environment.deepCopy());o.set("withoutIntervention",shadow.deepCopy());o.set("risk",risk.deepCopy());o.set("shadowRisk",shadowRisk.deepCopy());
        o.set("weather",weather==null?NullNode.instance:weather.deepCopy());o.set("decision",decision.deepCopy());
        o.put("responseModelVersion","m3-scenario-anomaly-20261002-v2");o.set("parameters",parameterRegistry());
        o.put("actionEffects","遮阳/补光影响PPFD及辐射热差；加热/湿帘影响温度，湿帘高湿进气时效果降低；通风/排风与模拟边界交换温度、水汽、CO₂，边界比棚内热时可能升温，并非总能降温；环流风机目前仅动画和空气混合示意，不改变棚均值；滴灌仅动画，不推算未知根区水分。");
        ObjectNode switches=mapper.createObjectNode();for(String c:CODES)switches.put(c,power(c)>0);
        o.set("devices",switches);o.set("deviceDuty",mapper.valueToTree(duty));o.set("deviceHealth",mapper.valueToTree(health));
        ObjectNode g=mapper.createObjectNode();g.put("plantHeightCm",Math.max(0,lastReferenceHeight+heightDelta));g.put("referenceHeightCm",lastReferenceHeight);
        g.put("withoutInterventionHeightCm",Math.max(0,lastReferenceHeight+shadowHeight));g.put("lai",cropState.getLai());g.put("wTotal",cropState.getWTotal());
        g.put("wFruit",cropState.getWFruit());g.put("stage",cropState.getStage().name());g.put("fieldValidated",false);o.set("growth",g);
        o.set("timeline",mapper.valueToTree(log.subList(Math.max(0,log.size()-80),log.size())));o.set("trends",mapper.valueToTree(trends));
        o.set("assumptions",mapper.valueToTree(Arrays.asList("事件、风雨、设备和室外边界为模拟，M3为棚内历史参考。",
            "换气、热量及水汽出力是未校准工程代理；通风干空气代理不来自室外测量。",
            "株高扰动为温度适宜度代理；生物量沿用未校准RUE。对照不是现场因果验证。",
            "土壤水分胁迫中性，灌溉暂不据未知土壤参数承诺长势改善；lux/PPFD换算仍有假设。")));
        return o;
    }
    private void record(String type,String text){ObjectNode e=mapper.createObjectNode();e.put("type",type);e.put("message",text);e.put("at",at.toString());e.put("tick",tick);e.put("source","SIMULATED");log.add(e);if(log.size()>400)log.remove(0);}
    private double range(double min,double max){return min+random.nextDouble()*(max-min);}
    private int integer(int min,int max){return min+random.nextInt(max-min+1);}
    private String title(String type){switch(type){case "STORM":return "暴雨";case "COLD_SNAP":return "骤降温";case "HEAT_WAVE":return "热浪";case "STRONG_WIND":return "强风";case "OVERCAST":return "连续阴雨";default:return "夜间高湿";}}
}
