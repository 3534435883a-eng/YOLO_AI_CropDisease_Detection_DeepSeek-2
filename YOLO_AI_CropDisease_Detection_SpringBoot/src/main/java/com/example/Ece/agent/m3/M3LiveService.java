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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.annotation.PreDestroy;

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
    private final M3RunJournal journal;
    private final ScheduledExecutorService clock=Executors.newSingleThreadScheduledExecutor(r->{
        Thread t=new Thread(r,"m3-model-clock");t.setDaemon(true);return t;
    });
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
        boolean playing,restored;
        int stepCount=1;
        long persistedAt,persistedVersion=-1;
        int persistedCursor=-1;
        String persistenceError;
    }
    public M3LiveService(M3ObservationService observations,M3MultiYearCalibrationService calibration,
                         TomatoCropGrowthModel crop,ObjectMapper mapper) {
        this.observations=observations;this.calibration=calibration;this.crop=crop;this.mapper=mapper;
        this.journal=new M3RunJournal(observations.root(),mapper);
        clock.scheduleWithFixedDelay(this::tickActiveRuns,1,1,TimeUnit.SECONDS);
    }
    public synchronized ObjectNode start(int year) throws IOException {
        long now=System.currentTimeMillis();
        runs.entrySet().removeIf(e->{Run old=e.getValue();synchronized(old){if(!old.playing&&!old.scenario.pending()&&now-old.accessed>1800000){persist(old,true);if(old.persistenceError!=null)return false;old.scenario.close();return true;}return false;}});
        reserveCapacity();
        Run r=initialize(year,UUID.randomUUID().toString(),calibration.latest(),new Random().nextLong());
        runs.put(r.id,r);persist(r,true);
        return snapshot(r,0,0);
    }
    private Run initialize(int year,String id,JsonNode saved,long seed) throws IOException {
        JsonNode data=observations.read(),season=null;
        for(JsonNode s:data.path("seasons"))if(s.path("year").asInt()==year)season=s;
        if(season==null)throw new IOException("仅支持已导入的2023/2024/2025");
        Run r=new Run();r.id=id;r.year=year;r.season=season;
        r.parameterVersion=saved.path("version").asText();r.parameters=saved.path("parameters").deepCopy();
        r.inputHash=data.path("observationsSha256").asText();r.accessed=System.currentTimeMillis();
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
        for(JsonNode plantId:season.path("cohort").path("plantIds")) {
            Plant plant=new Plant();plant.id=plantId.asText();
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
        r.scenario=new ScenarioSession(r.id,seed,mapper,crop,r.cropState,r.linear,r.frames.get(0));
        return r;
    }
    private JsonNode measurement(Run r,String id,String metric) {
        Map<String,JsonNode> rows=r.measurements.get(r.time.toLocalDate()+"|"+id);
        return rows==null?null:rows.get(metric);
    }
    public ObjectNode step(String id,int expectedCursor,int count) throws IOException {
        Run r=find(id);
        synchronized(r) {
            if(r.playing)throw new IOException("自动运行中，请先暂停再单步推进");
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
            r.accessed=System.currentTimeMillis();persist(r,true);
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
        M3RunJournal.validateId(id);
        Run r=runs.get(id);
        if(r==null)synchronized(this){r=runs.get(id);if(r==null){reserveCapacity();r=restore(id);runs.put(id,r);}}
        return r;
    }
    private void reserveCapacity() throws IOException {
        if(runs.size()<8)return;
        Run candidate=null;
        for(Run r:runs.values())synchronized(r){if(!r.playing&&!r.scenario.pending()&&(candidate==null||r.accessed<candidate.accessed))candidate=r;}
        if(candidate==null)throw new IOException("已有8个运行正在推进或等待AI，请暂停一个后再新建");
        synchronized(candidate){if(candidate.playing||candidate.scenario.pending())throw new IOException("运行状态已变化，请稍后再试");persist(candidate,true);if(candidate.persistenceError!=null)throw new IOException("旧运行不能存档，请恢复存储后再新建");runs.remove(candidate.id,candidate);candidate.scenario.close();}
    }
    public ObjectNode current(String id) throws IOException {
        return current(id,false);
    }
    public ObjectNode current(String id,boolean compact) throws IOException {
        return current(id,compact,-1);
    }
    public ObjectNode current(String id,boolean compact,int afterCursor) throws IOException {
        if(afterCursor < -1)throw new IOException("历史游标无效");
        Run r=find(id);synchronized(r){
            r.accessed=System.currentTimeMillis();persist(r,false);
            int from=0,eventFrom=0;
            if(compact){from=r.frames.size();eventFrom=r.events.size();}
            else if(afterCursor>=0){
                if(afterCursor>r.cursor)throw new IOException("历史游标超过当前运行，请重新读取");
                from=Math.min(afterCursor+1,r.frames.size());
                LocalDateTime cutoff=LocalDateTime.parse(r.frames.get(afterCursor).path("at").asText());
                while(eventFrom<r.events.size()&&!LocalDateTime.parse(r.events.get(eventFrom).path("at").asText()).isAfter(cutoff))eventFrom++;
            }
            return snapshot(r,from,eventFrom);
        }
    }
    /** Release a run; its task-linked checkpoint remains available for review. */
    public void delete(String id){Run r=runs.get(id);if(r!=null)synchronized(r){r.playing=false;persist(r,true);if(r.persistenceError!=null)throw new IllegalStateException("存档失败，运行仍保留："+r.persistenceError);runs.remove(id,r);r.scenario.close();}}
    public ObjectNode playback(String id,boolean playing,int stepCount) throws IOException {
        if(stepCount!=1&&stepCount!=12&&stepCount!=48)throw new IOException("步数须为1、12或48");
        Run r=find(id);synchronized(r){
            if(playing&&(r.failure!=null||r.cursor>=r.season.path("environment").size()))throw new IOException("运行已结束或失败，请新建运行");
            if(playing&&r.persistenceError!=null){persist(r,true);if(r.persistenceError!=null)throw new IOException("存档不可用，不能开始自动运行："+r.persistenceError);}
            r.playing=playing;r.stepCount=stepCount;r.accessed=System.currentTimeMillis();persist(r,true);
            return snapshot(r,r.frames.size(),r.events.size());
        }
    }
    private void tickActiveRuns() {
        for(Run r:runs.values())synchronized(r){
            if(!r.playing)continue;
            int total=r.season.path("environment").size();
            try {
                for(int i=0;i<r.stepCount&&r.cursor<total;i++){
                    if(r.scenario.pending())break;
                    advance(r);
                    if(r.cursor<total&&r.scenario.needsDecision()&&scenarioAi!=null)
                        scenarioAi.requestDecision(r.scenario,"请结合当前农情、根区与设备健康处理事件，观察改善与代价",true);
                }
                if(r.cursor>=total)r.playing=false;
            } catch(RuntimeException e){r.failure=e.getMessage();r.playing=false;}
            r.accessed=System.currentTimeMillis();persist(r,!r.playing);
            if(r.persistenceError!=null)r.playing=false;
        }
    }
    @PreDestroy public void shutdownClock(){clock.shutdownNow();for(Run r:runs.values())synchronized(r){r.playing=false;persist(r,true);r.scenario.close();}}

    private ObjectNode checkpoint(Run r) {
        ObjectNode out=mapper.createObjectNode();out.put("schemaVersion",1);out.put("runId",r.id);
        out.put("year",r.year);out.put("cursor",r.cursor);out.put("at",r.time.toString());
        out.put("parameterVersion",r.parameterVersion);out.put("inputHash",r.inputHash);out.set("parameters",r.parameters);
        out.put("stepCount",r.stepCount);out.put("pairs",r.pairs);out.put("updateCount",r.updateCount);
        if(r.failure!=null)out.put("failure",r.failure);
        out.set("errors",mapper.valueToTree(r.errors));out.set("cropState",mapper.valueToTree(r.cropState));
        ArrayNode plants=out.putArray("plants");for(Plant p:r.plants){ObjectNode n=plants.addObject();n.put("id",p.id);n.put("initialHeight",p.initialHeight);n.put("openAge",p.openAge);n.put("age",p.filter.age());n.put("variance",p.filter.variance());}
        out.set("frames",mapper.valueToTree(r.frames));out.set("events",mapper.valueToTree(r.events));
        out.set("risk",r.risk.checkpoint());out.set("scenario",r.scenario.checkpoint());
        out.put("savedAt",ZonedDateTime.now(ZoneId.of("Asia/Shanghai")).toString());return out;
    }
    private void persist(Run r,boolean force) {
        try{
            long now=System.currentTimeMillis(),version=r.scenario.snapshot().path("version").asLong();
            if(!force&&(now-r.persistedAt<5000||r.persistedCursor==r.cursor&&r.persistedVersion==version))return;
            journal.write(r.id,checkpoint(r));r.persistedAt=now;r.persistedCursor=r.cursor;r.persistedVersion=version;r.persistenceError=null;
        }
        catch(IOException|RuntimeException e){r.persistenceError=e.getMessage();r.playing=false;}
    }
    private Run restore(String id) throws IOException {
        JsonNode saved=journal.read(id),latest=calibration.latest();
        if(!latest.path("version").asText().equals(saved.path("parameterVersion").asText()))throw new IOException("参数版本已变化，存档需使用原版本审阅；请新建运行");
        ObjectNode frozen=mapper.createObjectNode();frozen.put("version",saved.path("parameterVersion").asText());frozen.set("parameters",saved.path("parameters"));
        Run r=initialize(saved.path("year").asInt(),id,frozen,saved.path("scenario").path("seed").asLong());
        if(!r.inputHash.equals(saved.path("inputHash").asText()))throw new IOException("M3数据版本已变化，不能混合恢复");
        int cursor=saved.path("cursor").asInt(-1);
        if(cursor<0||cursor>r.season.path("environment").size()||saved.path("frames").size()!=cursor+1)throw new IOException("运行存档游标不一致");
        try {
            r.cursor=cursor;r.time=LocalDateTime.parse(saved.path("at").asText());r.stepCount=saved.path("stepCount").asInt(1);
            if(r.stepCount!=1&&r.stepCount!=12&&r.stepCount!=48)throw new IOException("存档回放步长无效");
            if(!r.time.equals(LocalDate.parse(r.season.path("startDate").asText()).atTime(12,0).plusMinutes(cursor*30L)))throw new IOException("存档模型时间与游标不一致");
            r.pairs=saved.path("pairs").asInt();r.updateCount=saved.path("updateCount").asInt();
            for(int i=0;i<r.errors.length;i++)r.errors[i]=saved.path("errors").path(i).asDouble();
            for(Plant p:r.plants){JsonNode found=null;for(JsonNode row:saved.path("plants"))if(p.id.equals(row.path("id").asText()))found=row;if(found==null)throw new IOException("植株状态缺失");p.openAge=found.path("openAge").asDouble();p.filter.restore(found.path("age").asDouble(),found.path("variance").asDouble());}
            JsonNode c=saved.path("cropState");r.cropState=new TomatoCropState(c.path("gdd").asDouble(),c.path("lai").asDouble(),c.path("plantHeightCm").asDouble(),c.path("wleaf").asDouble(c.path("wLeaf").asDouble()),c.path("wstem").asDouble(c.path("wStem").asDouble()),c.path("wroot").asDouble(c.path("wRoot").asDouble()),c.path("wfruit").asDouble(c.path("wFruit").asDouble()),c.path("wtotal").asDouble(c.path("wTotal").asDouble()),c.path("fruitSetRate").asDouble(),c.path("fruitCount").asInt(),c.path("singleFruitWeightG").asDouble(),c.path("temperatureFactor").asDouble(),c.path("co2Factor").asDouble(),c.path("waterFactor").asDouble(),CropStage.valueOf(c.path("stage").asText()),c.path("mature").asBoolean());
            r.frames.clear();for(JsonNode f:saved.path("frames"))r.frames.add((ObjectNode)f.deepCopy());
            r.events.clear();for(JsonNode e:saved.path("events"))r.events.add((ObjectNode)e.deepCopy());
            r.risk.restoreCheckpoint(saved.path("risk"));r.scenario.restoreCheckpoint(saved.path("scenario"));
            r.failure=saved.hasNonNull("failure")?saved.path("failure").asText():null;
            r.playing=false;r.restored=true;persist(r,true);return r;
        } catch(RuntimeException e){throw new IOException("运行存档不能恢复："+e.getMessage(),e);}
    }
    public ObjectNode scenarioCommand(String id,String operation,JsonNode input) throws IOException {
        Run r=find(id);synchronized(r){
            if(r.cursor>=r.season.path("environment").size())throw new IOException("该运行已结束，请重开接入");
            switch(operation){
                case "configure":
                    if(input.hasNonNull("taskId")){
                        if(scenarioAi==null)throw new IOException("AI服务不可用");
                        String taskId=input.path("taskId").asText().trim();scenarioAi.validateTask(r.scenario,taskId);r.scenario.bindTask(taskId);
                    }
                    r.scenario.configure(input.path("autoEvents").asBoolean(),input.path("autoActuation").asBoolean());break;
                case "trigger":r.scenario.trigger(input.path("type").asText());break;
                case "endWeather":r.scenario.endWeather();break;
                case "repair":r.scenario.repair(input.path("device").asText());break;
                case "decide":
                    if(scenarioAi==null)throw new IOException("AI服务不可用");
                    String question=input.path("question").asText("分析当前状态");
                    if(question.length()>6000)throw new IllegalArgumentException("问题最多6000字");
                    scenarioAi.requestDecision(r.scenario,question,input.path("apply").asBoolean(false),input.hasNonNull("taskId")?input.path("taskId").asText():r.scenario.snapshot().path("farmTaskId").asText(null));break;
                default:throw new IllegalArgumentException("未知模拟操作");
            }
            if(!"decide".equals(operation)&&r.scenario.needsDecision()&&scenarioAi!=null)
                scenarioAi.requestDecision(r.scenario,"请处理当前事件并观察反馈",true);
            r.accessed=System.currentTimeMillis();persist(r,true);return snapshot(r,r.frames.size(),r.events.size());
        }
    }
    private ObjectNode snapshot(Run r,int from,int eventFrom) {
        ObjectNode out=mapper.createObjectNode();out.put("runId",r.id);out.put("year",r.year);out.put("cursor",r.cursor);
        out.put("totalSlots",r.season.path("environment").size());out.put("finished",r.cursor==r.season.path("environment").size());
        out.put("source","M3_HISTORICAL_STREAM");out.put("parameterVersion",r.parameterVersion);out.put("observationsSha256",r.inputHash);
        out.put("updateCount",r.updateCount);out.put("independentlyValidated",false);out.set("parameters",r.parameters);
        ObjectNode playback=out.putObject("playback");playback.put("playing",r.playing);playback.put("stepCount",r.stepCount);playback.put("waitingForAi",r.scenario.pending());playback.put("restored",r.restored);
        playback.put("checkpointIntervalSeconds",5);if(r.persistenceError!=null)playback.put("persistenceError",r.persistenceError);
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
