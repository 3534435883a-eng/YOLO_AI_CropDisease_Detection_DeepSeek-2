package com.example.Ece.agent.plan;

/**
 * 推演被调用方主动取消（典型场景：用户关掉了页面）。
 *
 * <p>用异常而不是返回 {@code null} 来表达取消，是为了让它**没法被忽略**：
 * 取消后 {@code chatStream} 返回的是**半截正文**，它看起来和完整回答一模一样。
 * 若不显式短路，半截方案会被当成完整方案下发并落库——这正是本通道最需要避免的失败。</p>
 *
 * <p>调用方（控制器）应当**安静地结束**，既不下发 error 事件也不落库：用户主动取消不是故障。</p>
 */
public class DeductionCancelledException extends RuntimeException {

    public DeductionCancelledException() {
        super("推演已被取消");
    }
}
