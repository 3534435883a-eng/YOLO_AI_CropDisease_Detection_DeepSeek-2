package com.example.Ece.agent.eval;

/**
 * 评测对照档。各档使用同一初始状态、同一天气相位与同一时间步长，
 * 差异只来自"谁在做决策"，从而把决策方式的贡献单独隔离出来。
 *
 * <p>P0–P3 是原有四档对照。P4–P6 是**连续控制消融**：三者使用同一个连续 PID 控制器，
 * 唯一差别是保留哪些控制项（仅 P / P+I / P+I+D），用于在同一条环境轨迹上检验
 * "比例控制存在稳态偏差、积分项消除偏差、微分项抑制超调"这一控制论结论是否在本模型上成立。
 * 三档都只调节气候设备，灌溉仍沿用规则侧二值控制，以免把连续调节效果与灌溉制度变化混在一起。</p>
 */
public enum EvaluationStrategy {

    /** 无调控：设备全关，环境自然演进。 */
    P0_NONE,

    /** 人工固定策略：定时通风与灌溉，模拟凭经验管理的农户。 */
    P1_FIXED_MANUAL,

    /** 规则引擎自动：现有确定性规则策略（硬阈值 + 固定出力 + 二值开关）。 */
    P2_RULE_ENGINE,

    /** 智能体决策：在规则候选集上做短程沙盘推演后择优。 */
    P3_AGENT,

    /** 连续比例控制：误差一出现即按比例出力，但无积分项——预期仍有稳态偏差。 */
    P4_PID_PROPORTIONAL,

    /** 连续比例 + 积分控制：预期消除稳态偏差。 */
    P5_PID_PI,

    /** 完整连续 PID 控制：预期在消偏之外进一步抑制超调。 */
    P6_PID_FULL;

    /**
     * 报告与界面用的可读名。
     *
     * <p>P0–P3 是**业务语义档位**（使用者要选的就是这四档）；P4–P6 是"同一控制器只保留不同控制项"的
     * **离线消融**，作用是给评测报告提供证据，不是要使用者去选的场景，故名称里明写"离线消融"。
     * 与前端 `PRODUCT_STRATEGY_ORDER` 的划分保持一致。</p>
     */
    public String getLabel() {
        switch (this) {
            case P0_NONE:
                return "不做调控";
            case P1_FIXED_MANUAL:
                return "人工定时管理";
            case P2_RULE_ENGINE:
                return "规则自动调控";
            case P3_AGENT:
                // 注意：这一档做的是候选动作的前瞻仿真，不调用 LLM/RAG，故不称"智能体"。
                return "前瞻择优调控";
            case P4_PID_PROPORTIONAL:
                return "离线消融：仅 P";
            case P5_PID_PI:
                return "离线消融：P+I";
            case P6_PID_FULL:
                return "离线消融：PID";
            default:
                return name();
        }
    }

    /** 是否为面向使用者的业务档位（P0–P3）。 */
    public boolean isProductTier() {
        return this == P0_NONE || this == P1_FIXED_MANUAL || this == P2_RULE_ENGINE || this == P3_AGENT;
    }
}
