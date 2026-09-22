package com.example.Ece.agent.orchestrator;

import java.util.List;
import java.util.Map;

/** 编排层与 LLM 的边界：plan 决定下一步动作，compose 组织最终回答。 */
public interface LlmClient {

    String plan(List<Map<String, Object>> history);

    String compose(List<Map<String, Object>> history);
}
