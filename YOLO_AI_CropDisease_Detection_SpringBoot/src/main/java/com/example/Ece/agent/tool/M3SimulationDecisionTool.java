package com.example.Ece.agent.tool;
import com.example.Ece.agent.m3.M3LiveService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class M3SimulationDecisionTool implements AgentTool {
    private final M3LiveService live;private final ObjectMapper mapper;
    public M3SimulationDecisionTool(M3LiveService live,ObjectMapper mapper){this.live=live;this.mapper=mapper;}
    public String name(){return "simulation.decide";}
    public String description(){return "请求大模型分析当前仿真并提出结构化方案。用户明确要求执行时才应用仿真设备；否则仅提出方案。异步返回状态，已提交不等于已执行。";}
    public ToolPermission permission(){return ToolPermission.DRAFT;}
    public String inputSchemaJson(){return "{\"type\":\"object\",\"properties\":{},\"required\":[]}";}
    public Map<String,Object> execute(Map<String,Object> input)throws ToolException {
        String id=String.valueOf(input.get("simulationRunId"));if(id.equals("null"))throw new ToolException("请先关联一个M3运行");
        try{
            ObjectNode request=mapper.createObjectNode();request.put("question",String.valueOf(input.get("question")));
            request.put("apply",Boolean.TRUE.equals(input.get("applyAuthorized")));
            ObjectNode result=live.scenarioCommand(id,"decide",request);Map<String,Object> out=new LinkedHashMap<>();
            out.put("scenario",result.path("current").path("scenario"));out.put("runId",id);out.put("source","SIMULATED");
            out.put("note","请求已提交，当前分析中；是否执行由后续服务端状态表示，不能现在宣称已控制设备或解决风险。");
            out.put("stepSummary","已提交仿真AI分析请求");return out;
        }catch(Exception e){throw new ToolException(e.getMessage(),e);}
    }
}
