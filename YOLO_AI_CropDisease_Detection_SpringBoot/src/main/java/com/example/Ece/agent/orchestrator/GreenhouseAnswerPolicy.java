package com.example.Ece.agent.orchestrator;

/** Presentation wording is separate from the retained provenance and execution receipts. */
public final class GreenhouseAnswerPolicy {
    private GreenhouseAnswerPolicy() {}
    public static final String INSTRUCTION =
            "本次交流的业务背景是当前大棚：默认把所关联运行的当前状态作为场景内事实，据此判断和管理。"
            + "存在current.scenario.environment时，它是当前事件和设备作用后的管理状态；current.environment是回放参考，不要将两者混用。"
            + "直接说当前棚温、湿度、根区状态、当前事件与处置结果，不反复使用根据虚拟数据、在仿真中、非实测等前缀，也不逐项重述数据来源。"
            + "正常作答先回应问题和管理措施，来源字段、参数假设和算法边界留在技术说明中；用户明确询问数据来源、测量精度或真实设备接入时如实解释。"
            + "候选图像仍不是病害确诊，湿润代理不是叶湿实测；不编造未知值。新动作只按实际平台回执区分建议、处理中、已应用。"
            + "可说平台已应用方案，但不能声称未连接的实物设备已受控、病害已治愈或已经取得现场增产效果。";
}
