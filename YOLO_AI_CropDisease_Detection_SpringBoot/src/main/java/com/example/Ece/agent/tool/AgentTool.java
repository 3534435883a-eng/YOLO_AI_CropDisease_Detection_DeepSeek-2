package com.example.Ece.agent.tool;

import java.util.Map;

/** 智能体可调用的工具契约。 */
public interface AgentTool {

    String name();

    String description();

    ToolPermission permission();

    String inputSchemaJson();

    Map<String, Object> execute(Map<String, Object> input) throws ToolException;
}
