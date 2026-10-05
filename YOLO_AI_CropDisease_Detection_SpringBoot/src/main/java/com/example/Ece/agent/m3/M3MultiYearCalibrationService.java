package com.example.Ece.agent.m3;

import com.example.Ece.agent.crop.*;
import com.example.Ece.agent.model.SimulationState;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Fit only 2023/24, freeze before reading 2025 growth targets for evaluation. */
@Service
public class M3MultiYearCalibrationService {
    private final M3ObservationService observations;
    private final TomatoCropGrowthModel crop;
    private final ObjectMapper mapper;
    public M3MultiYearCalibrationService(M3ObservationService observations,TomatoCropGrowthModel crop,ObjectMapper mapper) {
        this.observations=observations;this.crop=crop;this.mapper=mapper;
    }
    public JsonNode latest() throws IOException {
        Path path=observations.root().resolve("runs/latest-multiyear.json");
        if(!Files.isRegularFile(path))throw new IOException("三年数据已导入；请先创建跨年校准结果");
        JsonNode result=mapper.readTree(path.toFile());
        if(!result.path("observationsSha256").asText().equals(observations.read().path("observationsSha256").asText()))
            throw new IOException("最新结果的数据版本已变化，请重新校准");
        return result;
    }
    private static class Season {
        JsonNode raw;
        int year;
        LocalDate start,end;
        TreeMap<LocalDate,Double> gdd=new TreeMap<>();
        Map<String,TreeMap<LocalDate,Double>> height=new LinkedHashMap<>();
        Season(JsonNode raw) throws IOException {
            this.raw=raw;year=raw.path("year").asInt();
            start=LocalDate.parse(raw.path("startDate").asText());end=LocalDate.parse(raw.path("endDate").asText());
            for(JsonNode p:raw.path("cohort").path("plantIds"))height.put(p.asText(),new TreeMap<LocalDate,Double>());
            for(JsonNode r:raw.path("growth")) {
                if(!r.path("selected").asBoolean()||!"plantHeightCm".equals(r.path("metric").asText())
                        ||!r.path("qualityFlags").isEmpty()||!r.path("value").isNumber())continue;
                String id=r.path("plantId").asText();LocalDate day=LocalDate.parse(r.path("observedDate").asText());
                if(height.get(id).put(day,r.path("value").asDouble())!=null)throw new IOException("株高重复: "+year+id+day);
            }
            for(String id:height.keySet())if(!height.get(id).containsKey(start))throw new IOException("初态株高缺失 "+id);
            double total=0;gdd.put(start,0.0);
            LocalDateTime expected=start.atTime(12,0);
            for(JsonNode r:raw.path("environment")) {
                LocalDateTime time=LocalDateTime.parse(r.path("at").asText());
                if(!time.equals(expected))throw new IOException("环境时间不连续 "+year+" "+time);
                if(!r.path("temperatureC").isNumber())throw new IOException("环境温度缺失");
                total+=Math.max(0,r.path("temperatureC").asDouble()-TomatoGrowthParameters.BASE_TEMPERATURE_C)/48;
                expected=time.plusMinutes(30);
                if(expected.toLocalTime().equals(LocalTime.NOON))gdd.put(expected.toLocalDate(),total);
            }
            if(!expected.equals(end.atTime(12,0)))throw new IOException("环境窗口不完整 "+year);
        }
    }
    private double loss(List<Season> training,double p,double length) {
        double total=0;
        for(Season s:training) {
            double sum=0;int n=0;
            for(TreeMap<LocalDate,Double> plant:s.height.values()) {
                double initial=plant.get(s.start),age=ThermalInternodeHeightModel.initialThermalAge(initial,p,length);
                for(Map.Entry<LocalDate,Double> point:plant.entrySet()) {
                    if(point.getKey().equals(s.start))continue;
                    Double g=s.gdd.get(point.getKey());if(g==null)continue;
                    double e=ThermalInternodeHeightModel.heightAtAge(age+g,p,length)-point.getValue();
                    sum+=e*e;n++;
                }
            }
            if(n==0)throw new IllegalStateException("没有校准配对点");
            total+=sum/n;
        }
        return total/training.size();
    }
    private double[] fit(List<Season> training) {
        double best=Double.POSITIVE_INFINITY, bp=37.5,bl=7;
        double pl=25,ph=50,ll=2,lh=20;
        for(int round=0;round<5;round++) {
            double dp=(ph-pl)/20,dl=(lh-ll)/20;
            for(int i=0;i<=20;i++)for(int j=0;j<=20;j++) {
                double p=pl+i*dp,l=ll+j*dl,v=loss(training,p,l);
                if(v<best){best=v;bp=p;bl=l;}
            }
            pl=Math.max(25,bp-dp);ph=Math.min(50,bp+dp);
            ll=Math.max(2,bl-dl);lh=Math.min(20,bl+dl);
        }
        return new double[]{bp,bl,best};
    }
    private double linearFit(List<Season> training) {
        double x=0,y=0;
        for(Season s:training) {
            int n=0;double a=0,b=0;
            for(TreeMap<LocalDate,Double> plant:s.height.values())for(Map.Entry<LocalDate,Double> r:plant.entrySet()) {
                if(r.getKey().equals(s.start))continue;
                double g=s.gdd.get(r.getKey());a+=g*(r.getValue()-plant.get(s.start));b+=g*g;n++;
            }
            x+=a/n;y+=b/n;
        }
        return Math.max(0,Math.min(1,x/y));
    }
    public synchronized JsonNode calibrate() throws IOException {
        JsonNode data=observations.read();List<Season> training=new ArrayList<>();
        for(JsonNode s:data.path("seasons"))if(s.path("year").asInt()==2023||s.path("year").asInt()==2024)training.add(new Season(s));
        if(training.size()!=2)throw new IOException("需要2023与2024校准批次");
        double[] params=fit(training);double linear=linearFit(training);
        // Only now construct the held-out year's target table. Its first measurement is the allowed initial condition.
        List<Season> all=new ArrayList<>(training);
        for(JsonNode s:data.path("seasons"))if(s.path("year").asInt()==2025)all.add(new Season(s));
        if(all.size()!=3)throw new IOException("需要2025年份保留批次");
        ObjectNode result=mapper.createObjectNode();
        String version="m3-cross-year-"+LocalDateTime.now(ZoneId.of("Asia/Shanghai")).toString().replace(":","-")+"-"+UUID.randomUUID().toString().substring(0,8);
        result.put("schemaVersion",2);result.put("version",version);
        result.put("createdAt",ZonedDateTime.now(ZoneId.of("Asia/Shanghai")).toString());
        result.put("source","M3_OBSERVATION_DRIVEN");result.put("recommendedAsDefault",false);
        result.put("independentSeasonEvaluated",true);result.put("fieldValidated",false);
        for(String k:new String[]{"datasetVersion","observationsSha256","sourceUrl","paperUrl","license","publisherMd5","archiveBytes","sources","quality","assumptions"})result.set(k,data.path(k));
        ObjectNode p=mapper.createObjectNode();
        p.put("algorithm","THERMAL_INTERNODE_LOGISTIC_V1");p.put("phyllochronGdd",params[0]);p.put("maxInternodeLengthCm",params[1]);
        p.put("elongationK",ThermalInternodeHeightModel.ELONGATION_K);p.put("midpointGdd",ThermalInternodeHeightModel.MIDPOINT_GDD);
        p.put("baseTemperatureC",TomatoGrowthParameters.BASE_TEMPERATURE_C);p.put("linearHeightCmPerGdd",linear);
        p.put("trainingBalancedMse",params[2]);p.put("fitMethod","bounded grid + 4 local refinements; year-balanced squared height errors");
        p.put("atBound",Math.abs(params[0]-25)<0.001||Math.abs(params[0]-50)<0.001||Math.abs(params[1]-2)<0.001||Math.abs(params[1]-20)<0.001);
        p.put("luxPerRawUnit",1000);p.put("ppfdPerLux",0.0185);p.put("parUmolPerJoule",4.57);
        p.put("soilStressModelPct",60);p.put("initialLaiAssumption",1.3);p.put("initialLeafDryMassGPerM2",65);
        p.set("trainingYears",mapper.valueToTree(Arrays.asList(2023,2024)));p.put("heldOutYear",2025);
        ObjectNode sensitivity=mapper.createObjectNode();
        sensitivity.put("phyllochronPlus1PctMse",loss(training,Math.min(50,params[0]*1.01),params[1]));
        sensitivity.put("phyllochronMinus1PctMse",loss(training,Math.max(25,params[0]*0.99),params[1]));
        sensitivity.put("lengthPlus1PctMse",loss(training,params[0],Math.min(20,params[1]*1.01)));
        sensitivity.put("lengthMinus1PctMse",loss(training,params[0],Math.max(2,params[1]*0.99)));
        p.set("localSensitivity",sensitivity);result.set("parameters",p);
        ArrayNode seasons=mapper.createArrayNode();
        for(Season s:all)seasons.add(simulate(s,params,linear));
        result.set("seasons",seasons);
        JsonNode held=seasons.get(2).path("scores");
        double before=held.path("original").path("rmse").asDouble(),after=held.path("corrected").path("rmse").asDouble();
        result.put("status",after<before?"HELD_OUT_YEAR_IMPROVEMENT":"NO_HELD_OUT_YEAR_IMPROVEMENT");
        if(before>0)result.put("improvementPct",(before-after)/before*100);else result.putNull("improvementPct");
        ObjectNode snapshot=mapper.createObjectNode();
        for(Class<?> c:new Class<?>[]{ThermalInternodeHeightModel.class,M3MultiYearCalibrationService.class,TomatoCropGrowthModel.class,TomatoGrowthParameters.class}) {
            Path path=Paths.get("src/main/java/"+c.getName().replace('.','/')+".java");
            if(Files.isRegularFile(path))snapshot.put(c.getSimpleName()+"SourceSha256",M3ObservationService.sha256(Files.readAllBytes(path)));
            else try(InputStream input=c.getResourceAsStream("/"+c.getName().replace('.','/')+".class")) {
                if(input==null)throw new IOException("模型版本无法冻结");
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;
                while((n=input.read(b))!=-1)bytes.write(b,0,n);
                snapshot.put(c.getSimpleName()+"BytecodeSha256",M3ObservationService.sha256(bytes.toByteArray()));
            }
        }
        result.set("modelSnapshot",snapshot);
        ArrayNode notes=mapper.createArrayNode();
        notes.add("2023/2024仅同品种CK批次拟合；2025只用首日株高初始化，其余株高不参与补值、拟合、参数搜索或模型选择。");
        notes.add("初态热年龄通过首日株高反求，是形态假设；无逐节测量。固定k、中点与基温未按广辉201实测辨识，不是完整TOMGRO/GroIMP复现。");
        notes.add("参数可能相关，仅有两季训练；有限扰动敏感性不等于可辨识性证明，不提供未经验证的置信区间。");
        notes.add("环境为CK区域代表输入；缺测参考其他测点后用2023/2024日内均值补值，每个时段均保留来源。光照klux解释与太阳光谱转换尚未核实。");
        notes.add("原模型、线性积温、节间模型使用同一温度与环境；原模型保持已有机制。比较不能将形态变化归因于单个生理参数。");
        notes.add("LAI、器官干重、产量、设备控制、病害未校准；大棚叶果与设备是示意。公开数据不代表本项目现场，未自动发布默认参数。");
        result.set("notes",notes);
        result.set("algorithmReferences",mapper.valueToTree(Arrays.asList("https://doi.org/10.1093/insilicoplants/diaf022","https://github.com/mnqoliveira/data-assimilation-tomato-models","https://www.apogeeinstruments.com/conversion-ppfd-to-lux/")));
        Path dir=observations.root().resolve("runs");Files.createDirectories(dir);
        byte[] bytes=mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(result);
        Files.write(dir.resolve(version+".json"),bytes,StandardOpenOption.CREATE_NEW);
        Path temp=dir.resolve("latest-multiyear.tmp");Files.write(temp,bytes);
        try{Files.move(temp,dir.resolve("latest-multiyear.json"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        catch(AtomicMoveNotSupportedException e){Files.move(temp,dir.resolve("latest-multiyear.json"),StandardCopyOption.REPLACE_EXISTING);}
        return result;
    }
    private ObjectNode simulate(Season s,double[] p,double linear) {
        Map<String,TomatoCropState> old=new LinkedHashMap<>();Map<String,Double> ages=new LinkedHashMap<>();
        for(String id:s.height.keySet()) {
            double h=s.height.get(id).get(s.start);
            old.put(id,new TomatoCropState(0,1.3,h,65,39,52,0,156,0,0,0,1,1,1,CropStage.SEEDLING,false));
            ages.put(id,ThermalInternodeHeightModel.initialThermalAge(h,p[0],p[1]));
        }
        ArrayNode series=mapper.createArrayNode();
        series.add(frame(s,s.start,old,ages,p,linear,0,s.raw.path("environment").get(0)));
        double gdd=0;
        for(JsonNode d:s.raw.path("environment")) {
            LocalDateTime t=LocalDateTime.parse(d.path("at").asText());double temp=d.path("temperatureC").asDouble(),rh=d.path("airHumidityPct").asDouble();
            double es=0.6108*Math.exp(17.27*temp/(temp+237.3));
            SimulationState env=new SimulationState(t,temp,rh,60,d.path("co2Ppm").asDouble(),d.path("lightRaw").asDouble()*1000*0.0185,6.5,Math.max(0,es*(1-rh/100)),0,0,"NOT_EVALUATED");
            for(String id:old.keySet())old.put(id,crop.advance(old.get(id),env,30));
            gdd+=Math.max(0,temp-TomatoGrowthParameters.BASE_TEMPERATURE_C)/48;
            LocalDateTime next=t.plusMinutes(30);
            if(next.toLocalTime().equals(LocalTime.NOON))series.add(frame(s,next.toLocalDate(),old,ages,p,linear,gdd,d));
        }
        ObjectNode out=mapper.createObjectNode();
        for(String key:new String[]{"year","role","startDate","endDate","cohort","quality","environment"})out.set(key,s.raw.path(key));
        out.set("series",series);out.set("scores",scores(series));
        return out;
    }
    private ObjectNode frame(Season s,LocalDate day,Map<String,TomatoCropState> old,Map<String,Double> ages,double[] p,double linear,double gdd,JsonNode env) {
        ObjectNode f=mapper.createObjectNode();f.put("date",day.toString());f.put("gdd",gdd);
        f.put("split",day.equals(s.start)?"initial":s.year==2025?"holdout":"calibration");f.set("environment",env);
        ArrayNode plants=mapper.createArrayNode();double original=0,corrected=0,lin=0,obs=0;int count=0;
        for(String id:old.keySet()) {
            double h=ThermalInternodeHeightModel.heightAtAge(ages.get(id)+gdd,p[0],p[1]),l=s.height.get(id).get(s.start)+linear*gdd;
            ObjectNode row=mapper.createObjectNode();row.put("plantId",id);row.put("originalHeightCm",old.get(id).getPlantHeightCm());
            row.put("correctedHeightCm",h);row.put("linearHeightCm",l);
            Double measured=s.height.get(id).get(day);
            if(measured!=null){row.put("observedHeightCm",measured);obs+=measured;count++;}else row.putNull("observedHeightCm");
            plants.add(row);original+=old.get(id).getPlantHeightCm();corrected+=h;lin+=l;
        }
        f.set("plants",plants);f.put("originalHeightCm",original/old.size());f.put("correctedHeightCm",corrected/old.size());f.put("linearHeightCm",lin/old.size());
        if(count>0)f.put("observedHeightCm",obs/count);else f.putNull("observedHeightCm");
        f.put("measurementCount",count);return f;
    }
    private ObjectNode scores(ArrayNode series) {
        ObjectNode scores=mapper.createObjectNode();
        for(String key:new String[]{"original","corrected","linear"}) {
            int n=0;double abs=0,sq=0,bias=0;Set<String> dates=new HashSet<>(),ids=new HashSet<>();ArrayNode residuals=mapper.createArrayNode();
            for(JsonNode f:series) {
                if("initial".equals(f.path("split").asText()))continue;
                for(JsonNode p:f.path("plants")) {
                    if(!p.path("observedHeightCm").isNumber())continue;
                    double e=p.path(key+"HeightCm").asDouble()-p.path("observedHeightCm").asDouble();
                    n++;abs+=Math.abs(e);sq+=e*e;bias+=e;dates.add(f.path("date").asText());ids.add(p.path("plantId").asText());
                    ObjectNode r=mapper.createObjectNode();r.put("date",f.path("date").asText());r.put("plantId",p.path("plantId").asText());r.put("errorCm",e);residuals.add(r);
                }
            }
            ObjectNode metric=mapper.createObjectNode();metric.put("n",n);metric.put("dateCount",dates.size());metric.put("plantCount",ids.size());metric.put("unit","cm");
            if(n>0){metric.put("mae",abs/n);metric.put("rmse",Math.sqrt(sq/n));metric.put("bias",bias/n);}else{metric.putNull("mae");metric.putNull("rmse");metric.putNull("bias");}
            metric.set("residuals",residuals);scores.set(key,metric);
        }
        return scores;
    }
}

