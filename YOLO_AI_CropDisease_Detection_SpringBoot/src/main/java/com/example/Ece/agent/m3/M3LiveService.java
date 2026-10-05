package com.example.Ece.agent.m3;

import com.example.Ece.agent.crop.*;
import com.example.Ece.agent.model.SimulationState;
import com.example.Ece.agent.m3.scenario.ScenarioSession;
import com.example.Ece.agent.m3.scenario.ScenarioAiService;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Causal observation replay. Only consumed frames and measurements leave this service. */
@Service
public class M3LiveService {
    private final M3ObservationService observations;
    private final M3MultiYearCalibrationService calibration;
    private final TomatoCropGrowthModel crop;
    private final ObjectMapper mapper;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private ScenarioAiService scenarioAi;
    private final Map<String,Run> runs=new ConcurrentHashMap<>();
    private static class Plant {
        String id; double initialHeight,openAge; OnlineInternodeFilter filter;
    }
    private static class Run {
        String id,parameterVersion,inputHash,failure; int year,cursor; volatile long accessed;
        double p,length,linear;
        JsonNode season,parameters;
        final List<Plant> plants=new ArrayList<>();
        final Map<String,Map<String,JsonNode>> measurements=new HashMap<>();
        final List<ObjectNode> frames=new ArrayList<>();
        final List<ObjectNode> events=new ArrayList<>();
        final double[] errors=new double[9]; int pairs,updateCount;
        TomatoCropState cropState;
        M3LiveRiskEngine risk;
        LocalDateTime time;
        ScenarioSession scenario;
    }
    public M3LiveService(M3ObservationService observations,M3MultiYearCalibrationService calibration,
                         TomatoCropGrowthModel crop,ObjectMapper mapper) {
        this.observations=observations;this.calibration=calibration;this.crop=crop;this.mapper=mapper;
    }
    public synchronized ObjectNode start(int year) throws IOException {
        long now=System.currentTimeMillis();
        runs.entrySet().removeIf(e->{if(now-e.getValue().accessed>1800000){e.getValue().scenario.close();return true;}return false;});
        if(runs.size()>=8)throw new IOException("在线运行达到8个，请结束旧运行或稍后重试");
        JsonNode data=observations.read(),saved=calibration.latest(),season=null;
        for(JsonNode s:data.path("seasons"))if(s.path("year").asInt()==year)season=s;
        if(season==null)throw new IOException("仅支持已导入的2023/2024/2025");
        Run r=new Run();r.id=UUID.randomUUID().toString();r.year=year;r.season=season;
        r.parameterVersion=saved.path("version").asText();r.parameters=saved.path("parameters").deepCopy();
        r.inputHash=data.path("observationsSha256").asText();r.accessed=now;
        r.p=r.parameters.path("phyllochronGdd").asDouble();r.length=r.parameters.path("maxInternodeLengthCm").asDouble();
        r.linear=r.parameters.path("linearHeightCmPerGdd").asDouble();
        if(r.p<25||r.p>50||r.length<2||r.length>20)throw new IOException("冻结参数无效");
        r.time=LocalDate.parse(season.path("startDate").asText()).atTime(12,0);
        if(season.path("environment").size()==0)throw new IOException("环境时序为空");
        LocalDateTime expected=r.time;
        for(JsonNode env:season.path("environment")) {
            if(!expected.toString().equals(env.path("at").asText())
                    && !expected.equals(LocalDateTime.parse(env.path("at").asText())))throw new IOException("环境时间不连续");
            for(String k:new String[]{"temperatureC","airHumidityPct","co2Ppm","lightRaw"})
                if(!env.path(k).isNumber()||!Double.isFinite(env.path(k).asDouble()))throw new IOException("环境字段无效:"+k);
            expected=expected.plusMinutes(30);
        }
        for(JsonNode row:season.path("growth")) {
            if(!row.path("selected").asBoolean()||!row.path("qualityFlags").isEmpty()||!row.path("value").isNumber())continue;
            String key=row.path("observedDate").asText()+"|"+row.path("plantId").asText();
            r.measurements.computeIfAbsent(key,k->new HashMap<>()).put(row.path("metric").asText(),row);
        }
        double h=0;
        for(JsonNode id:season.path("cohort").path("plantIds")) {
            Plant plant=new Plant();plant.id=id.asText();
            JsonNode initial=measurement(r,plant.id,"plantHeightCm");
            if(initial==null)throw new IOException("首日株高缺失:"+plant.id);
            plant.initialHeight=initial.path("value").asDouble();
            plant.filter=new OnlineInternodeFilter(plant.initialHeight,r.p,r.length);plant.openAge=plant.filter.age();
            r.plants.add(plant);h+=plant.initialHeight;
        }
        if(r.plants.isEmpty())throw new IOException("可比植株为空");
        r.cropState=new TomatoCropState(0,1.3,h/r.plants.size(),65,39,52,0,156,0,0,0,1,1,1,CropStage.SEEDLING,false);
        r.risk=new M3LiveRiskEngine(mapper);
        JsonNode env=season.path("environment").get(0);
        ObjectNode risk=r.risk.evaluate(env,0);
        r.frames.add(frame(r,env,risk,Collections.emptyList(),true));
        r.scenario=new ScenarioSession(r.id,new Random().nextLong(),mapper,crop,r.cropState,r.linear,r.frames.get(0));
        runs.put(r.id,r);
        return snapshot(r,0,0);
    }
    private JsonNode measurement(Run r,String id,String metric) {
        Map<String,JsonNode> rows=r.measurements.get(r.time.toLocalDate()+"|"+id);
        return rows==null?null:rows.get(metric);
    }
    public ObjectNode step(String id,int expectedCursor,int count) throws IOException {
        Run r=find(id);
        synchronized(r) {
            if(r.failure!=null)throw new IOException("运行计算已停止："+r.failure+"；请重新开始");
            if(r.cursor!=expectedCursor)throw new IOException("游标已变化，请刷新当前运行");
            if(count!=1&&count!=12&&count!=48)throw new IOException("步数须为1、12或48");
            int from=r.frames.size(),eventFrom=r.events.size();
            try{for(int i=0;i<count&&r.cursor<r.season.path("environment").size();i++){
                if(r.scenario.pending())break;
                advance(r);
                if(r.cursor<r.season.path("environment").size()&&r.scenario.needsDecision()&&scenarioAi!=null)scenarioAi.requestDecision(r.scenario,"请处理当前事件，结合设备健康观察并缓解风险",true);
            }}
            catch(RuntimeException e){r.failure=e.getMessage();throw new IOException("运行已停止："+r.failure+"；请重新开始",e);}
            r.accessed=System.currentTimeMillis();
            return snapshot(r,from,eventFrom);
        }
    }
    private void advance(Run r) {
        JsonNode env=r.season.path("environment").get(r.cursor);
        double t=env.path("temperatureC").asDouble(),rh=env.path("airHumidityPct").asDouble();
        double gdd=Math.max(0,t-TomatoGrowthParameters.BASE_TEMPERATURE_C)/48;
        for(Plant p:r.plants){p.openAge+=gdd;p.filter.predict(gdd,1.0/48,env.path("estimated").asBoolean());}
        double es=0.6108*Math.exp(17.27*t/(237.3+t)),vpd=Math.max(0,es*(1-rh/100));
        SimulationState state=new SimulationState(r.time,t,rh,60,env.path("co2Ppm").asDouble(),
                env.path("lightRaw").asDouble()*1000*0.0185,6.5,vpd,0,0,"NOT_EVALUATED");
        r.cropState=crop.advanceCalibrated(r.cropState,state,30,r.linear);
        r.cursor++;r.time=r.time.plusMinutes(30);
        List<ObjectNode> updates=new ArrayList<>();
        if(r.time.toLocalTime().equals(LocalTime.NOON))for(Plant p:r.plants) {
            JsonNode row=measurement(r,p.id,"plantHeightCm");
            if(row==null)continue;
            double observed=row.path("value").asDouble(),open=ThermalInternodeHeightModel.heightAtAge(p.openAge,r.p,r.length);
            OnlineInternodeFilter.Update u=p.filter.update(observed);
            addError(r.errors,0,open-observed);addError(r.errors,3,u.before-observed);addError(r.errors,6,u.after-observed);r.pairs++;r.updateCount++;
            ObjectNode e=mapper.createObjectNode();
            e.put("type","HEIGHT_UPDATE");e.put("at",r.time.toString());e.put("plantId",p.id);
            e.put("beforeCm",u.before);e.put("observedCm",observed);e.put("afterCm",u.after);
            e.put("gain",u.gain);e.put("measurementWeight",u.measurementWeight);e.put("innovationCm",u.innovation);
            e.put("innovationStdCm",u.innovationStd);e.put("ageGdd",p.filter.age());e.put("varianceGdd2",p.filter.variance());
            e.put("reviewRequired",Math.abs(u.innovation)>3*u.innovationStd);e.set("source",row.path("source"));
            updates.add(e);r.events.add(e);
        }
        ObjectNode risk=r.risk.evaluate(env,30);
        for(JsonNode change:risk.path("changes")) {
            ObjectNode event=(ObjectNode)change.deepCopy();event.put("type","RISK_CHANGE");event.put("at",r.time.toString());r.events.add(event);
        }
        ObjectNode next=frame(r,env,risk,updates,false);
        ObjectNode scenario=r.scenario.advance(next);scenario.remove("trends");scenario.remove("timeline");scenario.remove("parameters");
        next.set("scenario",scenario);r.frames.add(next);
    }
    private void addError(double[] sums,int offset,double e){sums[offset]+=Math.abs(e);sums[offset+1]+=e*e;sums[offset+2]+=e;}
    private ObjectNode frame(Run r,JsonNode env,ObjectNode risk,List<ObjectNode> updates,boolean initial) {
        ObjectNode f=mapper.createObjectNode();
        f.put("at",r.time.toString());f.put("date",r.time.toLocalDate().toString());f.put("cursor",r.cursor);
        f.put("receivedAt",ZonedDateTime.now(ZoneId.of("Asia/Shanghai")).toString());f.put("gdd",r.cropState.getGdd());
        f.put("split",initial?"initial":"online");f.set("environment",env.deepCopy());f.set("risk",risk);
        f.set("updates",mapper.valueToTree(updates));
        double open=0,online=0,prior=0,obs=0,laiObs=0,ldwObs=0;int n=0,nLai=0,nLdw=0;
        ArrayNode plants=mapper.createArrayNode();
        for(Plant p:r.plants) {
            double a=ThermalInternodeHeightModel.heightAtAge(p.openAge,r.p,r.length),b=p.filter.height(),c=b;
            for(ObjectNode u:updates)if(p.id.equals(u.path("plantId").asText()))c=u.path("beforeCm").asDouble();
            ObjectNode row=mapper.createObjectNode();row.put("plantId",p.id);row.put("originalHeightCm",a);
            row.put("correctedHeightCm",b);row.put("predictedHeightCm",c);row.put("linearHeightCm",p.initialHeight+r.linear*r.cropState.getGdd());
            JsonNode observed=r.time.toLocalTime().equals(LocalTime.NOON)?measurement(r,p.id,"plantHeightCm"):null;
            if(observed!=null){row.put("observedHeightCm",observed.path("value").asDouble());obs+=observed.path("value").asDouble();n++;}else row.putNull("observedHeightCm");
            if(r.time.toLocalTime().equals(LocalTime.NOON)) {
                JsonNode leaf=measurement(r,p.id,"canopyLai"),mass=measurement(r,p.id,"canopyLdw");
                if(leaf!=null){laiObs+=leaf.path("value").asDouble();nLai++;}
                if(mass!=null){ldwObs+=mass.path("value").asDouble();nLdw++;}
            }
            plants.add(row);open+=a;online+=b;prior+=c;
        }
        f.set("plants",plants);f.put("originalHeightCm",open/r.plants.size());f.put("correctedHeightCm",online/r.plants.size());
        f.put("predictedHeightCm",prior/r.plants.size());f.put("linearHeightCm",0);f.put("measurementCount",n);
        if(n>0)f.put("observedHeightCm",obs/n);else f.putNull("observedHeightCm");
        ObjectNode leaf=mapper.createObjectNode();
        if(nLai>0)leaf.put("laiRaw",laiObs/nLai);else leaf.putNull("laiRaw");
        if(nLdw>0)leaf.put("ldwRaw",ldwObs/nLdw);else leaf.putNull("ldwRaw");
        leaf.put("assimilated",false);leaf.put("unitStatus","INSTRUMENT_BASIS_NOT_ALIGNED");f.set("leafReference",leaf);
        ObjectNode growth=mapper.createObjectNode();
        growth.put("lai",r.cropState.getLai());growth.put("wTotal",r.cropState.getWTotal());
        growth.put("wFruit",r.cropState.getWFruit());growth.put("fruitSetRate",r.cropState.getFruitSetRate());
        growth.put("fruitCount",r.cropState.getFruitCount());growth.put("singleFruitWeightG",r.cropState.getSingleFruitWeightG());
        growth.put("stage",r.cropState.getStage().name());growth.put("temperatureFactor",r.cropState.getTemperatureFactor());
        growth.put("co2Factor",r.cropState.getCo2Factor());growth.put("waterFactor",r.cropState.getWaterFactor());
        growth.put("plantHeightCm",online/r.plants.size());growth.put("fieldValidated",false);growth.put("soilStressNeutralAssumption",true);
        growth.put("lightUnitAssumption","raw klux -> lux x1000 -> PPFD x0.0185");f.set("growth",growth);
        return f;
    }
    private Run find(String id) throws IOException {
        Run r=runs.get(id);
        if(r==null)throw new IOException("在线运行不存在，请重新开始");
        if(System.currentTimeMillis()-r.accessed>1800000){runs.remove(id);r.scenario.close();throw new IOException("运行30分钟无活动已释放，请重新开始");}
        return r;
    }
    public ObjectNode current(String id) throws IOException {
        return current(id,false);
    }
    public ObjectNode current(String id,boolean compact) throws IOException {
        Run r=find(id);synchronized(r){r.accessed=System.currentTimeMillis();return snapshot(r,compact?r.frames.size():0,compact?r.events.size():0);}
    }
    public void delete(String id){Run r=runs.remove(id);if(r!=null)synchronized(r){r.scenario.close();}}
    public ObjectNode scenarioCommand(String id,String operation,JsonNode input) throws IOException {
        Run r=find(id);synchronized(r){
            if(r.cursor>=r.season.path("environment").size())throw new IOException("该运行已结束，请重开接入");
            switch(operation){
                case "configure":r.scenario.configure(input.path("autoEvents").asBoolean(),input.path("autoActuation").asBoolean());break;
                case "trigger":r.scenario.trigger(input.path("type").asText());break;
                case "endWeather":r.scenario.endWeather();break;
                case "repair":r.scenario.repair(input.path("device").asText());break;
                case "decide":
                    if(scenarioAi==null)throw new IOException("AI服务不可用");
                    scenarioAi.requestDecision(r.scenario,input.path("question").asText("分析当前状态"),input.path("apply").asBoolean(false));break;
                default:throw new IllegalArgumentException("未知模拟操作");
            }
            if(!"decide".equals(operation)&&r.scenario.needsDecision()&&scenarioAi!=null)
                scenarioAi.requestDecision(r.scenario,"请处理当前事件并观察反馈",true);
            r.accessed=System.currentTimeMillis();return snapshot(r,r.frames.size(),r.events.size());
        }
    }
    private ObjectNode snapshot(Run r,int from,int eventFrom) {
        ObjectNode out=mapper.createObjectNode();out.put("runId",r.id);out.put("year",r.year);out.put("cursor",r.cursor);
        out.put("totalSlots",r.season.path("environment").size());out.put("finished",r.cursor==r.season.path("environment").size());
        out.put("source","M3_HISTORICAL_STREAM");out.put("parameterVersion",r.parameterVersion);out.put("observationsSha256",r.inputHash);
        out.put("updateCount",r.updateCount);out.put("independentlyValidated",false);out.set("parameters",r.parameters);
        if(r.failure!=null)out.put("failure",r.failure);
        ObjectNode filter=mapper.createObjectNode();filter.put("measurementVarianceCm2",25);filter.put("processVarianceCm2PerDay",4);
        filter.put("estimatedInputVarianceMultiplier",4);filter.put("algorithm","SCALAR_EKF_THERMAL_AGE_JOSEPH_V1");out.set("filter",filter);
        out.put("riskRuleVersion","m3-exposure-rules-20261002");
        out.put("sourceUrl","https://zenodo.org/records/17217565");out.put("license","CC BY 4.0");
        out.set("references",mapper.valueToTree(Arrays.asList("https://filterpy.readthedocs.io/en/latest/kalman/ExtendedKalmanFilter.html",
                "https://extension.psu.edu/managing-leaf-mold-in-high-tunnels",
                "https://www.umass.edu/agriculture-food-environment/greenhouse-floriculture/fact-sheets/botrytis-blight-of-greenhouse-crops")));
        ObjectNode current=r.frames.get(r.frames.size()-1).deepCopy();current.set("scenario",r.scenario.snapshot());
        out.set("current",current);out.set("frames",mapper.valueToTree(r.frames.subList(from,r.frames.size())));
        out.set("events",mapper.valueToTree(r.events.subList(eventFrom,r.events.size())));
        ObjectNode scores=mapper.createObjectNode();
        for(int i=0;i<3;i++) {
            ObjectNode score=mapper.createObjectNode();score.put("n",r.pairs);score.put("unit","cm");
            if(r.pairs>0){score.put("mae",r.errors[i*3]/r.pairs);score.put("rmse",Math.sqrt(r.errors[i*3+1]/r.pairs));score.put("bias",r.errors[i*3+2]/r.pairs);}
            else {score.putNull("mae");score.putNull("rmse");score.putNull("bias");}
            scores.set(new String[]{"openLoop","prior","posterior"}[i],score);
        }
        out.set("scores",scores);
        out.set("assumptions",mapper.valueToTree(Arrays.asList(
                "M3历史数据按时间回放；参数来自2023/2024，2025新观测到达时用于状态更新。不是现场传感器。",
                "株高日期按12:00对齐；无测量时只预测，不造测量。初态排除。",
                "EKF测量标准差5cm、过程方差4cm²/天、估计输入过程方差乘4是工程假设，未根据2025结果调参。",
                "原始LAI/LDW仅参考，未对齐面积/仪器单位；RUE生长参考、果实估算与初始LAI/干重未校准。",
                "土壤VWC原值展示，作物水分胁迫为中性假设；光照klux/PPFD转换尚待核实。",
                "风险为工程阈值和持续暴露提示，不是发病概率；无叶面湿润、风速及病原观测。预警不控制实物。",
                "误差prior是新观测到达前的预测，posterior是使用本次观测后的拟合偏差，不能混作独立精度。")));
        return out;
    }
}
