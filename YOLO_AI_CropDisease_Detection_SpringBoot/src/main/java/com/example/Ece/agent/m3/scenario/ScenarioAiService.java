package com.example.Ece.agent.m3.scenario;

import com.example.Ece.agent.rag.*;
import com.example.Ece.agent.support.JsonBlockScanner;
import com.example.Ece.agent.task.FarmTaskService;
import com.example.Ece.dto.ai.ChatMessage;
import com.example.Ece.dto.ai.AiChatResponse;
import com.example.Ece.service.DeepSeekService;
import com.example.Ece.service.DeepSeekException;
import com.alibaba.fastjson.JSONObject;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import javax.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;

/** Calls the configured model; never presents a hard-coded policy as an AI response. */
@Service
public class ScenarioAiService {
    private final DeepSeekService model;
    private final ObjectMapper mapper;
    private final CitedKnowledgeReader knowledge;
    @Autowired private ObjectProvider<FarmTaskService> farmTasks;
    private final ExecutorService executor=Executors.newFixedThreadPool(2,r->{Thread t=new Thread(r,"m3-scenario-ai");t.setDaemon(true);return t;});
    public ScenarioAiService(DeepSeekService model,ObjectMapper mapper,CitedKnowledgeReader knowledge){this.model=model;this.mapper=mapper;this.knowledge=knowledge;}
    @PreDestroy public void close(){executor.shutdownNow();}
    public void requestDecision(ScenarioSession session,String question,boolean apply){
        requestDecision(session,question,apply,session.snapshot().path("farmTaskId").asText(null));
    }
    public void validateTask(ScenarioSession session,String taskId){if(taskId==null||taskId.trim().isEmpty())throw new IllegalArgumentException("请选择明确的农情任务");readTaskFacts(session,taskId);}
    private ObjectNode readTaskFacts(ScenarioSession session,String taskId){
        // Validate the explicit case before creating an in-flight device request.
        // ObjectProvider keeps the task/live/scenario service dependency lazy.
        final ObjectNode taskFacts;
        if(taskId==null||taskId.trim().isEmpty())taskFacts=null;
        else{
            String runId=session.snapshot().path("runId").asText();
            try{
                FarmTaskService tasks=farmTasks==null?null:farmTasks.getIfAvailable();
                if(tasks==null)throw new IllegalArgumentException("农情任务服务暂不可用，请刷新任务后再评估设备方案。");
                ObjectNode linked=tasks.context(taskId.trim(),runId);
                JsonNode task=linked.path("task");
                if(!task.isObject()||!runId.equals(task.path("simulationRunId").asText()))
                    throw new IllegalArgumentException("任务与当前大棚运行不匹配，请选择同一任务。");
                String crop=task.path("crop").asText();
                if(!crop.contains("番茄")&&!"tomato".equalsIgnoreCase(crop))
                    throw new IllegalArgumentException("当前大棚是番茄场景，请使用番茄任务评估虚拟设备方案。");
                taskFacts=compactFarmTask(task);
            }catch(IllegalArgumentException e){throw e;}
            catch(Exception e){
                String detail=e.getMessage();
                throw new IllegalArgumentException("无法读取或关联农情任务，请刷新任务后再试"+(detail==null?"。":"："+boundedText(detail,250)),e);
            }
        }
        return taskFacts;
    }
    public void requestDecision(ScenarioSession session,String question,boolean apply,String taskId){
        final ObjectNode taskFacts=readTaskFacts(session,taskId);
        if(taskFacts!=null)session.bindTask(taskFacts.path("id").asText());
        final ObjectNode context=session.beginDecision(question,apply);
        if(taskFacts!=null)context.set("farmTask",taskFacts);
        final String id=context.path("decision").path("requestId").asText();
        final long version=context.path("version").asLong();
        executor.submit(()->{
            try{
                ArrayNode sources=references(context);
                List<ChatMessage> messages=new ArrayList<>();
                messages.add(message("system",com.example.Ece.agent.orchestrator.GreenhouseAnswerPolicy.INSTRUCTION+"你是番茄温室的决策助手。先理解当前状态，再给可解释的设备目标。"
                    +"以当前事件与平台大棚状态为管理依据，summary、reason、expected直接描述棚内问题和处置作用，不反复声明数据是虚拟的；平台回执不能冒充未连接的实物执行，不保证病害或产量结果。"
                    +"请只返回一个JSON对象：{\"summary\":\"自然、具体的情况判断\",\"reason\":\"判断依据\","
                    +"\"actions\":[{\"device\":\"HEATING\",\"duty\":0.7,\"durationSteps\":4}],"
                    +"\"expected\":\"希望改善的量\",\"check\":\"至少两步后复查条件\",\"citationNumbers\":[1]}。"
                    +"actions最多10条，duty为0到1，durationSteps为2到24；只允许设备：HEATING,IRRIGATION,VENTILATION,"
                    +"durationSteps必须为整数；每步是30模拟分钟，2步=60模拟分钟，4步=2模拟小时；与界面播放速度和现实等待时间分开。"
                    +"SUPPLEMENTAL_LIGHT,SHADE,CO2_SUPPLY,ROOF_VENT,EXHAUST_FAN,COOLING_PAD,CIRCULATION_FAN。"
                    +"同设备不可重复。考虑已有开关，必要时显式关闭冲突设备。故障设备不能开启；风雨风速>=10时屋窗和侧窗须关闭；"
                    +"CO2_SUPPLY与通风/排风互锁；COOLING_PAD需要EXHAUST_FAN，不能同时HEATING。"
                    +"项目高温保护：棚内温度>=29°C时不能开启HEATING，这是工程保护条件，不是论文或品种通用阈值。"
                    +"冷湿天气平衡保温和排湿，高湿时湿帘效果下降；弱光补光。根据根区体积含水率、缺水/过湿暴露及假设流量判断是否灌溉，说明预计模拟水量和复查时段。"
                    +"区分棚内湿度与模拟进气边界湿度：低湿通常有利于蒸发降温；暴雨下高湿进气才会限制湿帘，不能颠倒这个关系。"
                    +"按actionEffects与agronomy解释实际响应：滴灌补入根区水量，蒸散和排水扣减，水分胁迫影响虚拟生长；环流仅加快冠层湿润代理干燥，不直接降低棚均温/湿。"
                    +"agronomy含水率单位为%vol，与标准或旧版相对含水率%不能直接比较；参数全部未标定，不将假设阈值说成品种标准。病害条件风险不能诊断病因，不得说病斑治愈。"
                    +"farmTask是同一农情任务的用户问题、候选图像证据、人工登记与复查。必须结合病叶等证据解释为何选择设备及需要哪些人工事项；这些记录不自动成为棚内传感器实测或病害确诊，识别置信度不能当病斑面积或病原量。"
                    +"人工完成记录与仿真设备回执分别理解；check中保留病斑、叶面和根区复查要求，设备环境改善不代表用户报告的症状已经消失。"
                    +"资源按出力×时间计量，可比较方案的耗水耗电代价；肥液EC、养分、病原与病斑面积尚未计算，不编造其改善或药剂用量。"
                    +"请像现场农技人员一样先回应农户的问题：抓主要矛盾、给可执行步骤、解释代价和复查条件。相同设备在不同天气下应有不同理由，不重复固定开场和万能方案。"
                    +"情况判断控制在160字内，理由控制在240字内；优先回应当前主要风险，用常用词，避免倾倒风险代码。"
                    +"风险阈值和设备出力是工程假设。无需为了显得积极而开启全部设备；可留空动作并解释需观察。"
                    +"citationNumbers最多4个，只写本次直接支持判断的主题编号，没有合适来源时为空，不能编造。"));
                ObjectNode compact=mapper.createObjectNode();
                if(taskFacts!=null)compact.set("farmTask",taskFacts);
                for(String key:new String[]{"runId","version","at","weather","environment","growth","deviceDuty","deviceHealth","question","actionEffects"})compact.set(key,context.path(key));
                compact.set("agronomy",compactAgronomy(context.path("agronomy")));
                compact.set("shadowAgronomy",compactAgronomy(context.path("shadowAgronomy")));
                compact.set("resources",context.path("resources"));compact.set("assumptions",context.path("assumptions"));
                ArrayNode effects=mapper.createArrayNode();for(JsonNode e:context.path("effects")){
                    if(!"IDLE".equals(e.path("status").asText()))effects.add(e);
                    if(effects.size()>=5)break;
                }compact.set("effects",effects);
                ObjectNode currentRisk=mapper.createObjectNode();currentRisk.set("riskLevel",context.path("risk").path("riskLevel"));currentRisk.set("vpdKpa",context.path("risk").path("vpdKpa"));
                ArrayNode alerts=mapper.createArrayNode();for(JsonNode raw:context.path("risk").path("alerts")){
                    if("ESTIMATED".equals(raw.path("code").asText()))continue;
                    ObjectNode a=mapper.createObjectNode();for(String key:new String[]{"code","title","level","value","threshold","unit","durationMinutes"})a.set(key,raw.path(key));alerts.add(a);
                }currentRisk.set("alerts",alerts);compact.set("risk",currentRisk);
                compact.set("referenceEnvironment",context.path("referenceEnvironment"));
                if(context.path("decision").path("feedback").isObject()){
                    JsonNode prior=context.path("decision").path("feedback");ObjectNode f=mapper.createObjectNode();
                    for(String key:new String[]{"at","temperatureDifferenceC","humidityDifferencePct","soilMoistureDifferenceVwcPct","resources"})f.set(key,prior.path(key));
                    for(String key:new String[]{"temperatureC","airHumidityPct","simulatedBoundaryTemperatureC","simulatedBoundaryHumidityPct"})f.set(key,prior.path("environment").path(key));
                    f.set("riskLevel",prior.path("risk").path("riskLevel"));f.set("vpdKpa",prior.path("risk").path("vpdKpa"));
                    f.set("agronomy",compactAgronomy(prior.path("agronomy")));compact.set("previousFeedback",f);
                }
                compact.set("recentTimeline",mapper.valueToTree(last(context.path("timeline"),4)));
                compact=com.example.Ece.agent.orchestrator.AgentContextSummary.promptData(compact);
                while(sources.size()>1&&compact.toString().length()+sources.toString().length()>11200)sources.remove(sources.size()-1);
                messages.add(message("user",compact.toString()+"\n经核对的研究和指导摘要："+sources));
                AiChatResponse response=model.chat(messages,DeepSeekService.ChatOptions.composingWithoutThinking());
                JSONObject parsed=JsonBlockScanner.firstObject(response==null?null:response.getContent(),o->o.containsKey("actions")&&o.containsKey("summary"));
                if(parsed==null)throw new IllegalArgumentException("模型没有返回完整设备方案");
                ObjectNode plan=(ObjectNode)mapper.readTree(parsed.toJSONString());
                if (plan.path("check").isTextual()) plan.put("check", normalizeReviewTime(plan.path("check").asText()));
                if(!plan.path("summary").isTextual()||plan.path("summary").asText().trim().isEmpty())throw new IllegalArgumentException("方案情况判断为空");
                ArrayNode used=mapper.createArrayNode();Set<Integer> seen=new HashSet<>();
                for(JsonNode n:plan.path("citationNumbers")){int no=n.asInt(-1);if(no>=1&&no<=sources.size()&&seen.add(no))used.add(sources.get(no-1));if(used.size()>=4)break;}
                plan.set("references",used);plan.put("modelReturned",true);
                if(taskFacts!=null){
                    plan.put("taskId",taskFacts.path("id").asText());
                    ArrayNode ids=plan.putArray("taskEvidenceIds");for(JsonNode e:taskFacts.path("evidence"))if(e.hasNonNull("id"))ids.add(e.path("id").asText());
                }
                session.applyPlan(plan,version,id,apply);
            }catch(Exception e){
                String reason=e instanceof DeepSeekException?((DeepSeekException)e).getCode():e.getClass().getSimpleName();
                String message=e.getMessage();session.decisionFailed(id,reason+"："+(message==null?"请求失败":message.substring(0,Math.min(350,message.length()))));
            }
        });
    }
    static String normalizeReviewTime(String check) {
        java.util.regex.Matcher match = java.util.regex.Pattern.compile("([0-9]{1,2}|两|二)\\s*步(后)?\\s*[（(]\\s*(?:约|至少)?\\s*[0-9]+(?:\\.[0-9]+)?\\s*(?:分钟|小时)(?:后)?\\s*[）)]").matcher(check);
        StringBuffer result = new StringBuffer();
        while (match.find()) {
            int steps = "两".equals(match.group(1)) || "二".equals(match.group(1)) ? 2 : Integer.parseInt(match.group(1));
            String corrected = steps + "步" + (match.group(2) == null ? "" : "后") + "（" + steps * 30 + "模拟分钟）";
            match.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(corrected));
        }
        match.appendTail(result); return result.toString();
    }

    private List<JsonNode> last(JsonNode rows,int count){List<JsonNode> list=new ArrayList<>();for(int i=Math.max(0,rows.size()-count);i<rows.size();i++)list.add(rows.get(i));return list;}
    private ObjectNode compactFarmTask(JsonNode task){
        ObjectNode o=mapper.createObjectNode();
        for(String key:new String[]{"id","crop","title","simulationRunId"})o.put(key,boundedText(task.path(key).asText(),180));
        o.put("question",boundedText(task.path("question").asText(),2200));
        o.put("sourceBoundary","用户所提供的事实与人工记录；图像结果为候选。不能冒充传感器实测、病害确诊或处理后疗效，模型仿真与这些证据分开判断。");
        for(String group:new String[]{"evidence","observations","actions"}){
            ArrayNode rows=o.putArray(group);JsonNode source=task.path(group);
            int start=Math.max(0,source.size()-4);
            List<JsonNode> selected=last(source,4);boolean retainedImage=false;
            // Keep the latest available image candidate even when later notes
            // would otherwise displace the symptom evidence from the recent set.
            if("evidence".equals(group))for(int i=source.size()-1;i>=0;i--){
                JsonNode old=source.get(i);String type=old.path("type").asText().toUpperCase(Locale.ROOT);
                if(old.hasNonNull("candidatesSummary")||type.contains("IMAGE")||type.contains("VISION")){
                    selected.remove(old);selected.add(old);retainedImage=i<start;break;
                }
            }
            for(JsonNode raw:selected){
                if(!raw.isObject())continue;
                ObjectNode row=rows.addObject();
                for(String key:new String[]{"id","type","label","title","source","status","reviewCondition","content","detail","note","recordedAt","candidatesSummary","detailsSummary"})
                    if(raw.hasNonNull(key))row.put(key,boundedText(raw.path(key).asText(),key.endsWith("Summary")?900:600));
                if("evidence".equals(group))row.put("diagnosisConfirmed",false);
            }
            if(start>0)o.put(group+"OmittedCount",start-(retainedImage?1:0));
        }
        while(o.toString().length()>7000){
            ArrayNode longest=null;
            for(String group:new String[]{"evidence","observations","actions"}){
                ArrayNode rows=(ArrayNode)o.path(group);
                if(rows.size()>1&&(longest==null||rows.toString().length()>longest.toString().length()))longest=rows;
            }
            if(longest==null)break;
            longest.remove(0);o.put("contextTruncated",true);
        }
        if(o.toString().length()>7000){
            o.put("question",boundedText(o.path("question").asText(),1200));o.put("contextTruncated",true);
            for(String group:new String[]{"evidence","observations","actions"})for(JsonNode raw:o.path(group)){
                ObjectNode row=(ObjectNode)raw;List<String> keys=new ArrayList<>();row.fieldNames().forEachRemaining(keys::add);
                for(String key:keys)if(row.path(key).isTextual())row.put(key,boundedText(row.path(key).asText(),250));
            }
        }
        if(o.toString().length()>7000){
            o.put("question",boundedText(o.path("question").asText(),600));
            for(String group:new String[]{"evidence","observations","actions"})for(JsonNode raw:o.path(group)){
                ObjectNode row=(ObjectNode)raw;List<String> keys=new ArrayList<>();row.fieldNames().forEachRemaining(keys::add);
                for(String key:keys)if(row.path(key).isTextual())row.put(key,boundedText(row.path(key).asText(),"id".equals(key)?160:120));
            }
        }
        if(o.toString().length()>7000)throw new IllegalArgumentException("农情任务摘要超过设备评估允许长度，请精简后重试。");
        return o;
    }
    private static String boundedText(String value,int max){return value.length()<=max?value:value.substring(0,max)+"…（摘要节选）";}
    private ObjectNode compactAgronomy(JsonNode root){
        ObjectNode o=mapper.createObjectNode();
        for(String key:new String[]{"origin","calibrated","soilMoistureUnit","soilMoistureVwcPct","rootCondition","fieldCapacityVwcPct","dryThresholdVwcPct","wetThresholdVwcPct",
            "waterStressFactor","waterUsedL","drainageL","evapotranspirationL","wetExposureMinutes","dryExposureMinutes","continuousWetMinutes","continuousDryMinutes",
            "canopyWetnessProxy","canopyWetMinutes","diseaseConditionLevel","diseaseConditions","riskLevel"})o.set(key,root.path(key));
        return o;
    }
    private ArrayNode references(JsonNode context){
        ArrayNode out=mapper.createArrayNode();List<KnowledgeSourceEntry> candidates=new ArrayList<>();
        for(KnowledgeSourceEntry e:knowledge.readAll())if("番茄".equals(e.getRecord().getCropType())&&referenceScore(e,context)>0)candidates.add(e);
        candidates.sort((a,b)->Integer.compare(referenceScore(b,context),referenceScore(a,context)));
        Map<String,Integer> selected=new HashMap<>();
        for(KnowledgeSourceEntry e:candidates){
            String code=e.getSource().getSourceCode();int count=selected.getOrDefault(code,0);
            if(count>=3)continue;selected.put(code,count+1);
            ObjectNode row=mapper.createObjectNode();row.put("number",out.size()+1);row.put("sourceCode",code);row.put("title",e.getSource().getSourceName());
            String text=String.join("；",e.getRecord().getFields().values());
            row.put("url",e.getSource().getUrl());row.put("topic",e.getRecord().getDiseaseName());row.put("text",text.length()>1000?text.substring(0,1000)+"（摘要节选）":text);out.add(row);
            if(out.size()>=12)break;
        }
        return out;
    }
    private int referenceScore(KnowledgeSourceEntry e,JsonNode context){
        String code=e.getSource().getSourceCode(),topic=e.getRecord().getDiseaseName();
        String question=context.path("question").asText()+" "+context.path("farmTask").path("question").asText()+" "+context.path("farmTask").path("evidence").toString();int score=0;
        if(code.startsWith("guidance-weather"))score=6;
        else if(code.startsWith("guidance-humidity"))score=7;
        else if(code.startsWith("paper-feedback"))score=1;
        else if(code.startsWith("std-nyt5449")||code.startsWith("std-db37t1849")){
            boolean relevant=e.getRecord().getFields().containsKey(KnowledgeChunk.FieldType.ENVIRONMENT)
                ||e.getRecord().getFields().containsKey(KnowledgeChunk.FieldType.WATER_FERT)
                ||e.getRecord().getFields().containsKey(KnowledgeChunk.FieldType.CTRL_AGRI)
                ||e.getRecord().getFields().containsKey(KnowledgeChunk.FieldType.CONTROL);
            if(relevant)score=4;
        }else if(code.startsWith("paper-niu")||code.startsWith("paper-wang")||code.startsWith("paper-sun"))score=3;
        if(score==0)return 0;
        boolean root="DRY".equals(context.path("agronomy").path("rootCondition").asText())
            ||"WET".equals(context.path("agronomy").path("rootCondition").asText())||question.contains("灌")||question.contains("浇")||question.contains("水肥")||question.contains("根区")||question.contains("土壤");
        if(root&&(topic.contains("水分")||topic.contains("灌溉")||topic.contains("水肥")))score+=8;
        if((question.contains("斑")||question.contains("病")||!"LOW".equals(context.path("agronomy").path("diseaseConditionLevel").asText("LOW")))
            &&(topic.contains("防治")||topic.contains("高湿")||topic.contains("结露")))score+=7;
        String weather=context.path("weather").path("type").asText();
        if(("COLD_SNAP".equals(weather)||"OVERCAST".equals(weather))&&(topic.contains("低温")||topic.contains("冷湿")||topic.contains("阴雨")))score+=5;
        if("HEAT_WAVE".equals(weather)&&(topic.contains("降温")||topic.contains("湿帘")))score+=5;
        if(question.contains("补光")&&topic.contains("光"))score+=7;
        return score;
    }
    private ChatMessage message(String role,String content){ChatMessage m=new ChatMessage();m.setRole(role);m.setContent(content);return m;}
}
