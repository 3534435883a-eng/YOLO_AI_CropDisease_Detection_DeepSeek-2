package com.example.Ece.agent.m3;

import com.example.Ece.agent.crop.*;
import com.example.Ece.agent.model.SimulationState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.lang.reflect.*;
import java.time.*;
import java.util.*;

/** Conditional offline calibration; fit dates and held-out dates are fixed before optimization. */
@Service
public class M3CalibrationService {
    private static final LocalDate START = LocalDate.of(2025,4,19), END = LocalDate.of(2025,6,13);
    private static final Set<LocalDate> TRAIN = new LinkedHashSet<LocalDate>(Arrays.asList(
            LocalDate.of(2025,4,26), LocalDate.of(2025,5,10), LocalDate.of(2025,5,17)));
    private static final Set<LocalDate> HOLDOUT = new LinkedHashSet<LocalDate>(Arrays.asList(
            LocalDate.of(2025,5,28), LocalDate.of(2025,5,31), LocalDate.of(2025,6,7), END));
    private static final String[] FIELDS = {"temperatureC", "airHumidityPct", "co2Ppm", "lightRaw", "soilMoistureVwcPct"};
    private final M3ObservationService observations;
    private final TomatoCropGrowthModel model;
    private final ObjectMapper mapper;
    public M3CalibrationService(M3ObservationService observations, TomatoCropGrowthModel model, ObjectMapper mapper) {
        this.observations=observations; this.model=model; this.mapper=mapper;
    }
    public JsonNode latest() throws IOException {
        Path p=observations.root().resolve("runs/latest.json");
        if (!Files.isRegularFile(p)) throw new IOException("尚未运行M3校准，请先确认数据假设并创建对照");
        return mapper.readTree(p.toFile());
    }

    private static class Driver {
        final LocalDateTime at;
        final double[] values;
        final boolean estimated;
        final String measuredAt;
        final String origin;
        Driver(LocalDateTime at,double[] values,boolean estimated,String measuredAt,String origin) {
            this.at=at;this.values=values;this.estimated=estimated;this.measuredAt=measuredAt;this.origin=origin;
        }
    }

    public synchronized JsonNode calibrate() throws IOException {
        final double lightScale = 1000;
        if (lightScale != 1 && lightScale != 1000) throw new IOException("光照情景只支持原值按lx或按klux解释");
        JsonNode data=observations.read();
        Map<String,Map<LocalDate,Double>> measured=new LinkedHashMap<String,Map<LocalDate,Double>>();
        for(JsonNode id:data.path("cohort").path("plantIds")) measured.put(id.asText(),new TreeMap<LocalDate,Double>());
        for(JsonNode row:data.path("growth")) {
            if(!"plantHeightCm".equals(row.path("metric").asText()) || !row.path("selected").asBoolean()
                    || !row.path("qualityFlags").isEmpty() || !row.path("value").isNumber()) continue;
            String id=row.path("plantId").asText();LocalDate date=LocalDate.parse(row.path("observedDate").asText());
            Double previous=measured.get(id).put(date,row.path("value").asDouble());
            if(previous!=null) throw new IOException("同株同日有重复株高，请先处理观测冲突");
        }
        for(Map.Entry<String,Map<LocalDate,Double>> plant:measured.entrySet())
            if(!plant.getValue().containsKey(START)) throw new IOException("缺少初始株高："+plant.getKey());

        TreeMap<LocalDateTime,JsonNode> climate=new TreeMap<LocalDateTime,JsonNode>();
        double[][] sum=new double[48][FIELDS.length]; int[] count=new int[48];
        LocalDateTime begin=START.atTime(12,0), end=END.atTime(12,0), trainEnd=LocalDate.of(2025,5,17).atTime(12,0);
        for(JsonNode row:data.path("environment")) {
            if(!row.path("qualityFlags").isEmpty()) continue;
            LocalDateTime time=OffsetDateTime.parse(row.path("observedAt").asText()).toLocalDateTime();
            // Use only past readings: 11:31:33 is represented at 12:00, never at 11:30.
            LocalDateTime bin=time.withSecond(0).withNano(0).withMinute(time.getMinute()<30?0:30);
            if(bin.isBefore(time))bin=bin.plusMinutes(30);
            if(climate.put(bin,row)!=null)throw new IOException("一个计算时段出现多个有效测点，请核对时间映射");
            if(!time.isBefore(begin) && time.isBefore(trainEnd)) {
                int slot=bin.getHour()*2+bin.getMinute()/30;
                count[slot]++;
                for(int j=0;j<FIELDS.length;j++)sum[slot][j]+=row.path(FIELDS[j]).asDouble();
            }
        }
        Map<LocalDateTime,JsonNode> references=new TreeMap<LocalDateTime,JsonNode>();
        for(JsonNode row:data.path("environmentReferences")) {
            LocalDateTime time=OffsetDateTime.parse(row.path("observedAt").asText()).toLocalDateTime();
            LocalDateTime bin=time.withSecond(0).withNano(0).withMinute(time.getMinute()<30?0:30);
            if(bin.isBefore(time))bin=bin.plusMinutes(30);
            // Import order deliberately gives same CK area priority over another treatment's climate.
            if(!references.containsKey(bin))references.put(bin,row);
        }
        List<Driver> drivers=new ArrayList<Driver>();int missing=0,referenceCount=0,meanCount=0;
        ArrayNode missingIntervals=mapper.createArrayNode();
        for(LocalDateTime time=begin;time.isBefore(end);time=time.plusMinutes(30)) {
            JsonNode row=climate.get(time);double[] values=new double[FIELDS.length];
            boolean estimated=row==null;int slot=time.getHour()*2+time.getMinute()/30;
            String origin="sensor1-contrast2-back";
            if(estimated) {
                missing++;missingIntervals.add(time.toString());
                if(count[slot]==0)throw new IOException("校准段没有该时段的环境均值，不能补缺："+time);
                JsonNode reference=references.get(time);
                if(reference!=null) {
                    for(int j=0;j<FIELDS.length;j++)values[j]=reference.path(FIELDS[j]).isNumber()
                            ?reference.path(FIELDS[j]).asDouble():sum[slot][j]/count[slot];
                    origin="reference:"+reference.path("sensorId").asText();referenceCount++;row=reference;
                } else {
                    for(int j=0;j<FIELDS.length;j++)values[j]=sum[slot][j]/count[slot];
                    origin="training-intraday-mean";meanCount++;
                }
            } else for(int j=0;j<FIELDS.length;j++)values[j]=row.path(FIELDS[j]).asDouble();
            drivers.add(new Driver(time,values,estimated,row==null?null:row.path("observedAt").asText(),origin));
        }

        Map<LocalDate,Double> dailyGdd=new TreeMap<LocalDate,Double>();double gdd=0;
        dailyGdd.put(START,0.0);
        for(Driver d:drivers) {
            gdd+=Math.max(0,d.values[0]-TomatoGrowthParameters.BASE_TEMPERATURE_C)/48.0;
            LocalDateTime next=d.at.plusMinutes(30);
            if(next.toLocalTime().equals(LocalTime.NOON))dailyGdd.put(next.toLocalDate(),gdd);
        }
        double numerator=0,denominator=0;int fitN=0;
        for(Map<LocalDate,Double> plant:measured.values())for(LocalDate date:TRAIN)if(plant.containsKey(date)) {
            double x=dailyGdd.get(date),y=plant.get(date)-plant.get(START);
            numerator+=x*y;denominator+=x*x;fitN++;
        }
        if(denominator<=0 || fitN<3)throw new IOException("有效积温或校准样本不足");
        double unconstrained=numerator/denominator,coefficient=Math.max(0,Math.min(1,unconstrained));
        Map<String,TomatoCropState> original=new LinkedHashMap<String,TomatoCropState>();
        Map<String,TomatoCropState> corrected=new LinkedHashMap<String,TomatoCropState>();
        for(String id:measured.keySet()) {
            TomatoCropState state=initial(measured.get(id).get(START));original.put(id,state);corrected.put(id,state);
        }
        ArrayNode series=mapper.createArrayNode(),driverJson=mapper.createArrayNode();int estimatedTotal=0;
        series.add(frame(START,original,corrected,measured,0,drivers.get(0),lightScale));
        for(Driver d:drivers) {
            if(d.estimated)estimatedTotal++;
            double ppfd=d.values[3]*lightScale*0.0185;
            double es=0.6108*Math.exp(17.27*d.values[0]/(d.values[0]+237.3));
            // VWC is not the model's moisture stress scale. Use neutral modeled water factor explicitly.
            SimulationState env=new SimulationState(d.at,d.values[0],d.values[1],60,d.values[2],ppfd,6.5,
                    Math.max(0,es*(1-d.values[1]/100)),0,0,"NOT_EVALUATED");
            for(String id:measured.keySet()) {
                original.put(id,model.advance(original.get(id),env,30));
                corrected.put(id,model.advanceCalibrated(corrected.get(id),env,30,coefficient));
            }
            ObjectNode dj=mapper.createObjectNode();dj.put("at",d.at.toString());dj.put("estimated",d.estimated);
            dj.put("origin",d.origin);
            if(d.measuredAt!=null)dj.put("observedAt",d.measuredAt);else dj.putNull("observedAt");
            for(int j=0;j<FIELDS.length;j++)dj.put(FIELDS[j],d.values[j]);
            dj.put("ppfdEstimated",ppfd);driverJson.add(dj);
            LocalDateTime next=d.at.plusMinutes(30);
            if(next.toLocalTime().equals(LocalTime.NOON))
                series.add(frame(next.toLocalDate(),original,corrected,measured,estimatedTotal,d,lightScale));
        }
        ObjectNode scores=mapper.createObjectNode();
        scores.set("calibration",scores(series,"calibration"));scores.set("holdout",scores(series,"holdout"));
        double before=scores.path("holdout").path("original").path("rmse").asDouble();
        double after=scores.path("holdout").path("corrected").path("rmse").asDouble();
        String version="m3-height-"+LocalDateTime.now(ZoneId.of("Asia/Shanghai")).toString().replace(":","-")+"-"+UUID.randomUUID().toString().substring(0,8);
        ObjectNode result=mapper.createObjectNode();result.put("version",version);result.put("source","M3_OBSERVATION_DRIVEN");
        result.put("createdAt",ZonedDateTime.now(ZoneId.of("Asia/Shanghai")).toString());
        result.put("status",before>after?"CONDITIONAL_IMPROVEMENT":"NO_HELDOUT_IMPROVEMENT");
        result.put("recommendedAsDefault",false);result.put("independentSeasonValidated",false);
        for(String key:new String[]{"datasetVersion","observationsSha256","sourceUrl","paperUrl","license","publisherMd5","cohort","quality","sources","fieldMappings"})result.set(key,data.get(key));
        result.set("series",series);result.set("driver",driverJson);result.set("scores",scores);
        result.put("improvementPct",before>0?(before-after)/before*100:0);
        ObjectNode parameters=mapper.createObjectNode();parameters.put("heightCmPerGdd",coefficient);
        parameters.put("unconstrainedHeightCmPerGdd",unconstrained);parameters.put("atBound",coefficient!=unconstrained);
        parameters.put("baseTemperatureC",TomatoGrowthParameters.BASE_TEMPERATURE_C);parameters.put("maxHeightCm",400);
        parameters.put("luxPerRawUnit",lightScale);parameters.put("ppfdPerLux",0.0185);parameters.put("parUmolPerJoule",4.57);
        parameters.put("soilStressModelPct",60);parameters.put("initialLaiAssumption",1.3);
        parameters.put("initialLeafDryMassGPerM2",65);parameters.put("initialGddAssumption",0);
        parameters.put("fitMethod","bounded origin least squares; one shared coefficient; training dates only");
        result.set("parameters",parameters);
        ObjectNode provenance=mapper.createObjectNode();
        for(Class<?> type:new Class<?>[]{TomatoGrowthParameters.class,TomatoCropGrowthModel.class}) {
            ObjectNode constants=mapper.createObjectNode();
            try {
                for(Field f:type.getDeclaredFields())if(Modifier.isStatic(f.getModifiers())&&f.getType()==double.class) {
                    f.setAccessible(true);constants.put(f.getName(),f.getDouble(null));
                }
                Path source=Paths.get("src/main/java/"+type.getName().replace('.','/')+".java");
                constants.put("sourceSha256",M3ObservationService.sha256(Files.readAllBytes(source)));
            } catch(Exception e) {throw new IOException("不能冻结模型参数/源码版本",e);}
            provenance.set(type.getSimpleName(),constants);
        }
        result.set("modelSnapshot",provenance);
        ObjectNode coverage=mapper.createObjectNode();coverage.put("totalSlots",drivers.size());coverage.put("estimatedSlots",missing);
        coverage.put("observedSlots",drivers.size()-missing);coverage.put("observedCoveragePct",100.0*(drivers.size()-missing)/drivers.size());
        coverage.put("policy","REFERENCE_THEN_TRAINING_INTRADAY_MEAN");coverage.put("referenceSlots",referenceCount);
        coverage.put("meanSlots",meanCount);coverage.set("estimatedIntervals",missingIntervals);
        coverage.put("climatologyEndExclusive",trainEnd.toString());result.set("inputCoverage",coverage);
        ArrayNode notes=mapper.createArrayNode();
        notes.add("该结果是同一批植株未来日期的条件性外推；非独立年份、非项目现场验证，未自动替换默认模型。");
        notes.add("主输入为sensor1对照2后区域代表环境，未核实到每株/重复小区；缺测先参考sensor2对照2、再参考sensor3处理1后，均标为区域替代估计。");
        notes.add("参考测点也缺测时，仅用04-19 12:00至05-17 12:00前的校准期环境日内均值；没有使用保留期长势来补值或拟合参数。");
        notes.add("原始时间保留；计算时段使用此前最近一次采样，约28分钟后的半小时网格承载读数。株高仅日期精度，统一对齐12:00。");
        notes.add("原始光照单位冲突：本次按"+(lightScale==1000?"klux":"lx")+"解释，非已核实单位；PPFD按太阳光谱近似，不能称实测PPFD。");
        notes.add("VWC为实测输入展示，水分胁迫暂用中性假设；冠层LAI、器官干重、物候、鲜果产量、病害和设备响应均未校准。");
        notes.add("初态株高来自每株04-19；LAI=1.3、叶/茎/根干重=65/39/52 g/m²、GDD=0为统一模型假设；保留段不重置状态。");
        result.set("notes",notes);
        ArrayNode conversionReferences=mapper.createArrayNode();conversionReferences.add("https://www.apogeeinstruments.com/conversion-ppfd-to-lux/");
        conversionReferences.add("https://ceac.arizona.edu/sites/default/files/thimijan_-_photmetric_radiometric_and_quantum_light_units.pdf");
        result.set("conversionReferences",conversionReferences);
        Path runs=observations.root().resolve("runs");Files.createDirectories(runs);
        byte[] bytes=mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(result);
        Files.write(runs.resolve(version+".json"),bytes,StandardOpenOption.CREATE_NEW);
        Path temp=runs.resolve("latest.tmp");Files.write(temp,bytes);
        try{Files.move(temp,runs.resolve("latest.json"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
        catch(AtomicMoveNotSupportedException e){Files.move(temp,runs.resolve("latest.json"),StandardCopyOption.REPLACE_EXISTING);}
        return result;
    }

    private TomatoCropState initial(double height) {
        return new TomatoCropState(0,1.3,height,65,39,52,0,156,0,0,0,1,1,1,CropStage.SEEDLING,false);
    }
    private ObjectNode frame(LocalDate day,Map<String,TomatoCropState> old,Map<String,TomatoCropState> fixed,
                            Map<String,Map<LocalDate,Double>> measured,int estimated,Driver input,double scale) {
        ObjectNode f=mapper.createObjectNode();f.put("date",day.toString());
        String split=day.equals(START)?"initial":TRAIN.contains(day)?"calibration":HOLDOUT.contains(day)?"holdout":"prediction";
        f.put("split",split);f.put("estimatedSlotsCumulative",estimated);
        ArrayNode plants=mapper.createArrayNode();double a=0,b=0,m=0;int n=0;
        for(String id:old.keySet()) {
            ObjectNode p=mapper.createObjectNode();p.put("plantId",id);p.put("originalHeightCm",old.get(id).getPlantHeightCm());
            p.put("correctedHeightCm",fixed.get(id).getPlantHeightCm());
            Double obs=measured.get(id).get(day);
            if(obs!=null){p.put("observedHeightCm",obs);m+=obs;n++;}else p.putNull("observedHeightCm");
            plants.add(p);a+=old.get(id).getPlantHeightCm();b+=fixed.get(id).getPlantHeightCm();
        }
        f.set("plants",plants);f.put("originalHeightCm",a/old.size());f.put("correctedHeightCm",b/old.size());
        if(n>0)f.put("observedHeightCm",m/n);else f.putNull("observedHeightCm");
        f.put("measurementCount",n);f.put("gdd",old.values().iterator().next().getGdd());
        ObjectNode env=mapper.createObjectNode();for(int j=0;j<FIELDS.length;j++)env.put(FIELDS[j],input.values[j]);
        env.put("estimated",input.estimated);env.put("lightPpfdEstimated",input.values[3]*scale*0.0185);
        f.set("environment",env);return f;
    }
    private ObjectNode scores(ArrayNode series,String split) {
        ObjectNode block=mapper.createObjectNode();
        for(String modelName:new String[]{"original","corrected"}) {
            int n=0;double abs=0,squared=0,bias=0;Set<String> ids=new HashSet<String>(),dates=new HashSet<String>();
            ArrayNode residuals=mapper.createArrayNode();
            for(JsonNode f:series)if(split.equals(f.path("split").asText()))for(JsonNode p:f.path("plants")) {
                if(!p.path("observedHeightCm").isNumber())continue;
                double e=p.path(modelName+"HeightCm").asDouble()-p.path("observedHeightCm").asDouble();
                n++;abs+=Math.abs(e);squared+=e*e;bias+=e;ids.add(p.path("plantId").asText());dates.add(f.path("date").asText());
                ObjectNode r=mapper.createObjectNode();r.put("date",f.path("date").asText());r.put("plantId",p.path("plantId").asText());r.put("errorCm",e);residuals.add(r);
            }
            ObjectNode metric=mapper.createObjectNode();metric.put("n",n);metric.put("plantCount",ids.size());metric.put("dateCount",dates.size());metric.put("unit","cm");
            if(n>0){metric.put("mae",abs/n);metric.put("rmse",Math.sqrt(squared/n));metric.put("bias",bias/n);}
            else{metric.putNull("mae");metric.putNull("rmse");metric.putNull("bias");}
            metric.set("residuals",residuals);block.set(modelName,metric);
        }
        return block;
    }
}
