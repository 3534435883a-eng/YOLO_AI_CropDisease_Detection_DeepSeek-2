package com.example.Ece.agent.tool;
import com.example.Ece.agent.m3.M3LiveService;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class M3SimulationSnapshotTool implements AgentTool {
    private final M3LiveService live;
    public M3SimulationSnapshotTool(M3LiveService live){this.live=live;}
    public String name(){return "simulation.snapshot";}
    public String description(){return "读取本轮服务端绑定的M3及随机事件大棚：环境、风险、设备、AI方案和实际仿真反馈。不能切换运行。";}
    public ToolPermission permission(){return ToolPermission.READ_ONLY;}
    public String inputSchemaJson(){return "{\"type\":\"object\",\"properties\":{},\"required\":[]}";}
    public Map<String,Object> execute(Map<String,Object> input)throws ToolException {
        String id=String.valueOf(input.get("simulationRunId"));
        if(id.equals("null")||id.isEmpty())throw new ToolException("请先关联一个M3大棚运行");
        try{
            ObjectNode run=live.current(id,true);Map<String,Object> out=new LinkedHashMap<>();
            out.put("snapshot",run.get("current"));out.put("runId",id);out.put("source","M3_HISTORICAL_REFERENCE_AND_SIMULATED_SCENARIO");
            out.put("note","M3原始环境和株高为历史观测；scenario内风雨、设备响应和环境为模型模拟。时间来自本轮最新快照。");
            out.put("stepSummary","已读取当前M3大棚和处置反馈");return out;
        }catch(Exception e){throw new ToolException(e.getMessage(),e);}
    }
}
