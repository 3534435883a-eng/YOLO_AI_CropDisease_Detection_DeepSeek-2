package com.example.Ece.agent.m3.scenario;

import com.example.Ece.agent.rag.*;
import com.example.Ece.agent.support.JsonBlockScanner;
import com.example.Ece.dto.ai.ChatMessage;
import com.example.Ece.dto.ai.AiChatResponse;
import com.example.Ece.service.DeepSeekService;
import com.example.Ece.service.DeepSeekException;
import com.alibaba.fastjson.JSONObject;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.stereotype.Service;
import javax.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;

/** Calls the configured model; never presents a hard-coded policy as an AI response. */
@Service
public class ScenarioAiService {
    private final DeepSeekService model;
    private final ObjectMapper mapper;
    private final CitedKnowledgeReader knowledge;
    private final ExecutorService executor=Executors.newFixedThreadPool(2,r->{Thread t=new Thread(r,"m3-scenario-ai");t.setDaemon(true);return t;});
    public ScenarioAiService(DeepSeekService model,ObjectMapper mapper,CitedKnowledgeReader knowledge){this.model=model;this.mapper=mapper;this.knowledge=knowledge;}
    @PreDestroy public void close(){executor.shutdownNow();}
    public void requestDecision(ScenarioSession session,String question,boolean apply){
        final ObjectNode context=session.beginDecision(question,apply);
        final String id=context.path("decision").path("requestId").asText();
        final long version=context.path("version").asLong();
        executor.submit(()->{
            try{
                ArrayNode sources=references();
                List<ChatMessage> messages=new ArrayList<>();
                messages.add(message("system","你是番茄温室模拟的决策助手。先理解当前状态，再给可解释的设备目标。"
                    +"这都是仿真，M3为棚内历史参考；室外风雨是随机事件。不能说已控制实物，不能保证病害或产量结果。"
                    +"请只返回一个JSON对象：{\"summary\":\"自然、具体的情况判断\",\"reason\":\"判断依据\","
                    +"\"actions\":[{\"device\":\"HEATING\",\"duty\":0.7,\"durationSteps\":4}],"
                    +"\"expected\":\"希望改善的量\",\"check\":\"至少两步后复查条件\",\"citationNumbers\":[1]}。"
                    +"actions最多10条，duty为0到1，durationSteps为2到24；只允许设备：HEATING,IRRIGATION,VENTILATION,"
                    +"SUPPLEMENTAL_LIGHT,SHADE,CO2_SUPPLY,ROOF_VENT,EXHAUST_FAN,COOLING_PAD,CIRCULATION_FAN。"
                    +"同设备不可重复。考虑已有开关，必要时显式关闭冲突设备。故障设备不能开启；风雨风速>=10时屋窗和侧窗须关闭；"
                    +"CO2_SUPPLY与通风/排风互锁；COOLING_PAD需要EXHAUST_FAN，不能同时HEATING。"
                    +"项目高温保护：棚内温度>=29°C时不能开启HEATING，这是工程保护条件，不是论文或品种通用阈值。"
                    +"冷湿天气平衡保温和排湿，高湿时湿帘效果下降；弱光补光，灌溉缺少土壤参数不能承诺改善。"
                    +"区分棚内湿度与模拟进气边界湿度：低湿通常有利于蒸发降温；暴雨下高湿进气才会限制湿帘，不能颠倒这个关系。"
                    +"按actionEffects解释本引擎实际能计算的响应，不把环流风机或滴灌的演示动画说成降低棚均温/湿或纠正根区的计算结果。"
                    +"情况判断控制在160字内，理由控制在240字内；优先回应当前主要风险，用常用词，避免倾倒风险代码。"
                    +"风险阈值和设备出力是工程假设。无需为了显得积极而开启全部设备；可留空动作并解释需观察。"
                    +"citationNumbers最多4个，只写本次直接支持判断的主题编号，没有合适来源时为空，不能编造。"));
                ObjectNode compact=mapper.createObjectNode();
                for(String key:new String[]{"runId","version","at","weather","environment","growth","deviceDuty","deviceHealth","question","actionEffects"})compact.set(key,context.path(key));
                ObjectNode currentRisk=mapper.createObjectNode();currentRisk.set("riskLevel",context.path("risk").path("riskLevel"));currentRisk.set("vpdKpa",context.path("risk").path("vpdKpa"));
                ArrayNode alerts=mapper.createArrayNode();for(JsonNode raw:context.path("risk").path("alerts")){
                    if("ESTIMATED".equals(raw.path("code").asText()))continue;
                    ObjectNode a=mapper.createObjectNode();for(String key:new String[]{"code","title","level","value","threshold","unit","durationMinutes"})a.set(key,raw.path(key));alerts.add(a);
                }currentRisk.set("alerts",alerts);compact.set("risk",currentRisk);
                compact.set("referenceEnvironment",context.path("referenceEnvironment"));
                if(context.path("decision").path("feedback").isObject()){
                    JsonNode prior=context.path("decision").path("feedback");ObjectNode f=mapper.createObjectNode();
                    for(String key:new String[]{"at","temperatureDifferenceC","humidityDifferencePct"})f.set(key,prior.path(key));
                    for(String key:new String[]{"temperatureC","airHumidityPct","simulatedBoundaryTemperatureC","simulatedBoundaryHumidityPct"})f.set(key,prior.path("environment").path(key));
                    f.set("riskLevel",prior.path("risk").path("riskLevel"));f.set("vpdKpa",prior.path("risk").path("vpdKpa"));compact.set("previousFeedback",f);
                }
                compact.set("recentTimeline",mapper.valueToTree(last(context.path("timeline"),4)));
                while(sources.size()>1&&compact.toString().length()+sources.toString().length()>11200)sources.remove(sources.size()-1);
                messages.add(message("user",compact.toString()+"\n经核对的研究和指导摘要："+sources));
                AiChatResponse response=model.chat(messages,DeepSeekService.ChatOptions.composingWithoutThinking());
                JSONObject parsed=JsonBlockScanner.firstObject(response==null?null:response.getContent(),o->o.containsKey("actions")&&o.containsKey("summary"));
                if(parsed==null)throw new IllegalArgumentException("模型没有返回完整设备方案");
                ObjectNode plan=(ObjectNode)mapper.readTree(parsed.toJSONString());
                if(!plan.path("summary").isTextual()||plan.path("summary").asText().trim().isEmpty())throw new IllegalArgumentException("方案情况判断为空");
                ArrayNode used=mapper.createArrayNode();Set<Integer> seen=new HashSet<>();
                for(JsonNode n:plan.path("citationNumbers")){int no=n.asInt(-1);if(no>=1&&no<=sources.size()&&seen.add(no))used.add(sources.get(no-1));}
                plan.set("references",used);plan.put("modelReturned",true);
                session.applyPlan(plan,version,id,apply);
            }catch(Exception e){
                String reason=e instanceof DeepSeekException?((DeepSeekException)e).getCode():e.getClass().getSimpleName();
                String message=e.getMessage();session.decisionFailed(id,reason+"："+(message==null?"请求失败":message.substring(0,Math.min(350,message.length()))));
            }
        });
    }
    private List<JsonNode> last(JsonNode rows,int count){List<JsonNode> list=new ArrayList<>();for(int i=Math.max(0,rows.size()-count);i<rows.size();i++)list.add(rows.get(i));return list;}
    private ArrayNode references(){
        ArrayNode out=mapper.createArrayNode();
        for(KnowledgeSourceEntry e:knowledge.readAll()){
            String code=e.getSource().getSourceCode();
            if(!code.startsWith("guidance-weather")&&!code.startsWith("paper-feedback")&&!code.startsWith("guidance-humidity"))continue;
            ObjectNode row=mapper.createObjectNode();row.put("number",out.size()+1);row.put("sourceCode",code);row.put("title",e.getSource().getSourceName());
            row.put("url",e.getSource().getUrl());row.put("topic",e.getRecord().getDiseaseName());row.put("text",String.join("；",e.getRecord().getFields().values()));out.add(row);
            if(out.size()>=16)break;
        }
        return out;
    }
    private ChatMessage message(String role,String content){ChatMessage m=new ChatMessage();m.setRole(role);m.setContent(content);return m;}
}
