package com.example.Ece.agent.m3;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** Transparent exposure rules, not validated disease probabilities. */
public final class M3LiveRiskEngine {
    private final ObjectMapper mapper;
    private final Map<String,Integer> duration=new LinkedHashMap<>();
    private final Map<String,String> previous=new HashMap<>();
    private final Deque<double[]> history=new ArrayDeque<>();
    public M3LiveRiskEngine(ObjectMapper mapper){this.mapper=mapper;}
    public ObjectNode evaluate(JsonNode env,int minutes) {
        double t=env.path("temperatureC").asDouble(),rh=env.path("airHumidityPct").asDouble();
        double es=0.6108*Math.exp(17.27*t/(t+237.3)),vpd=Math.max(0,es*(1-rh/100));
        double gamma=Math.log(Math.max(0.1,rh)/100)+17.27*t/(237.3+t);
        double dew=237.3*gamma/(17.27-gamma);
        ArrayNode active=mapper.createArrayNode(), changes=mapper.createArrayNode();
        rule(active,changes,"HEAT","高温暴露",t>=29,t>=34,t,29,"°C",minutes,60,
                "检查通风和遮阳，复核冠层温度","项目工程阈值，非广辉201标定值",env);
        rule(active,changes,"COLD","低温暴露",t<=14,t<=8,t,14,"°C",minutes,60,
                "检查保温与加热条件，核实传感器读数","项目工程阈值",env);
        rule(active,changes,"HUMID","高湿病害环境",rh>=85,false,rh,85,"%",minutes,360,
                "检查通风排湿与叶面湿润，必要时拍照进行病害识别","高湿为环境条件提示，缺少叶面湿润和病原观测",env);
        rule(active,changes,"VPD_HIGH","VPD偏高",vpd>2,vpd>3.5,vpd,2,"kPa",minutes,60,
                "检查温湿度和根区水分，综合评估蒸腾压力","由空气温湿度计算，工程阈值",env);
        rule(active,changes,"VPD_LOW","VPD偏低",vpd<0.3,false,vpd,0.3,"kPa",minutes,120,
                "检查空气流通和高湿持续时间","不使用叶温，不能判断实际叶面结露",env);
        rule(active,changes,"ESTIMATED","环境输入含补值",env.path("estimated").asBoolean(),false,0,0,"",minutes,Integer.MAX_VALUE,
                "复核原始测点与补值来源","数据质量提示，不是作物风险",env);
        if(minutes>0)history.addLast(new double[]{t>=29?minutes:0,rh>=85?minutes:0,vpd>2?minutes:0,Math.max(0,t-29)*minutes/60.0});
        while(history.size()>48)history.removeFirst();
        double hot=0,wet=0,dry=0,degree=0;
        for(double[] row:history){hot+=row[0];wet+=row[1];dry+=row[2];degree+=row[3];}
        ObjectNode out=mapper.createObjectNode();
        out.put("vpdKpa",vpd);out.put("dewPointC",dew);out.put("dewPointMarginC",t-dew);
        out.put("highTemperatureMinutes24h",hot);out.put("highHumidityMinutes24h",wet);
        out.put("highVpdMinutes24h",dry);out.put("heatDegreeHours24h",degree);
        out.put("windowMinutes",history.size()*minutes);
        out.set("alerts",active);out.set("changes",changes);
        String level="LOW";
        for(JsonNode alert:active){if("HIGH".equals(alert.path("level").asText())){level="HIGH";break;}if("MEDIUM".equals(alert.path("level").asText()))level="MEDIUM";}
        out.put("riskLevel",level);
        out.put("validatedProbability",false);
        return out;
    }
    private void rule(ArrayNode active,ArrayNode changes,String code,String title,boolean condition,boolean severe,
                      double value,double threshold,String unit,int minutes,int escalation,String advice,String basis,JsonNode env) {
        int elapsed=condition?duration.getOrDefault(code,0)+minutes:0;duration.put(code,elapsed);
        String level=condition?("ESTIMATED".equals(code)?"INFO":severe||elapsed>=escalation?"HIGH":"MEDIUM"):"CLEAR";
        ObjectNode row=mapper.createObjectNode();
        row.put("code",code);row.put("title",title);row.put("level",level);row.put("value",value);row.put("threshold",threshold);
        row.put("unit",unit);row.put("durationMinutes",elapsed);row.put("advice",advice);row.put("basis",basis);
        row.put("estimatedInput",env.path("estimated").asBoolean());row.put("origin",env.path("origin").asText());
        if(condition)active.add(row);
        String old=previous.getOrDefault(code,"CLEAR");
        if(!old.equals(level)){changes.add(row.deepCopy());previous.put(code,level);}
    }
}
