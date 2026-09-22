package com.example.Ece.agent.eval;

/**
 * 评测对照档。四档使用同一初始状态、同一天气相位与同一时间步长，
 * 差异只来自"谁在做决策"，从而把 AI 的贡献单独隔离出来。
 */
public enum EvaluationStrategy {

    /** 无调控：设备全关，环境自然演进。 */
    P0_NONE,

    /** 人工固定策略：定时通风与灌溉，模拟凭经验管理的农户。 */
    P1_FIXED_MANUAL,

    /** 规则引擎自动：现有确定性规则策略。 */
    P2_RULE_ENGINE,

    /** 智能体决策：在规则候选集上做短程沙盘推演后择优。 */
    P3_AGENT
}
