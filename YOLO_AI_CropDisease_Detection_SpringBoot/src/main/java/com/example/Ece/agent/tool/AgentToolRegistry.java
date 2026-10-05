package com.example.Ece.agent.tool;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 工具注册表：向编排层暴露可用工具与目录描述。 */
@Component
public class AgentToolRegistry {

    private final Map<String, AgentTool> tools = new LinkedHashMap<String, AgentTool>();

    public AgentToolRegistry() {
    }

    @Autowired(required = false)
    public void setTools(List<AgentTool> discovered) {
        if (discovered == null) {
            return;
        }
        for (AgentTool tool : discovered) {
            register(tool);
        }
    }

    public void register(AgentTool tool) {
        if (tool != null && tool.name() != null) {
            tools.put(tool.name(), tool);
        }
    }

    public AgentTool find(String name) {
        return name == null ? null : tools.get(name);
    }

    public List<AgentTool> all() {
        return new ArrayList<AgentTool>(tools.values());
    }

    public String catalogJson() {
        return catalogJson(true, true);
    }

    public String catalogJson(boolean simulationAvailable, boolean greenhouseStateAvailable) {
        StringBuilder builder = new StringBuilder("[");
        boolean first = true;
        for (AgentTool tool : tools.values()) {
            if (!simulationAvailable && tool.name().startsWith("simulation.")) continue;
            if (!greenhouseStateAvailable && "platform.greenhouseState".equals(tool.name())) continue;
            if (!first) {
                builder.append(",");
            }
            first = false;
            builder.append("{\"name\":\"").append(tool.name())
                    .append("\",\"description\":\"").append(tool.description())
                    .append("\",\"permission\":\"").append(tool.permission())
                    .append("\",\"inputSchema\":").append(tool.inputSchemaJson()).append("}");
        }
        return builder.append("]").toString();
    }
}
