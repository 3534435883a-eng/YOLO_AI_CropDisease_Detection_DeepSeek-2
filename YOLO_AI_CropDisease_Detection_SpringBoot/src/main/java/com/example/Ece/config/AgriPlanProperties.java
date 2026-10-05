package com.example.Ece.config;

import com.example.Ece.agent.profile.HortiM3Profile;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 农事规划推演通道的配置。
 *
 * <p>与 {@code AgriEnvironmentProperties} 同一条纪律：<b>中文默认值写在 Java 字段上，
 * 不写进 {@code application.properties}</b>——Spring Boot 对 {@code .properties} 默认按
 * ISO-8859-1 读取，中文会乱码（该项目实测踩过，接口曾返回 {@code æ¼ç¤º...}）。
 * 要在 properties 里覆盖中文值，请用 Unicode 转义写法。</p>
 *
 * <p>（这里刻意不把转义序列写出来：Java 在词法分析前就会处理源码中的 Unicode 转义，
 * 即使在注释里，写出来会直接编译失败。）</p>
 */
@ConfigurationProperties(prefix = "agent.plan")
public class AgriPlanProperties {

    /**
     * 是否允许推演给出具体农药剂量与安全间隔期。**默认关闭**。
     *
     * <p>这是整条推演通道唯一的真实伤害面：剂量与安全间隔期必须由当地登记与产品标签决定，
     * 凭模型记忆给出来可能直接造成药害或农残超标。关闭时只给防治方向与时机。
     * 演示或答辩需要放开时改这一项即可，不必改代码。</p>
     */
    private boolean allowPesticideDosage = false;

    /** 参考基线的默认推演天数。 */
    private int defaultDays = HortiM3Profile.DEFAULT_DAYS;

    /** 单次推演的整段超时（毫秒）。必须大于上游预期最长生成时间，见 SSE emitter 的超时预算。 */
    private long timeoutMs = 240_000L;

    /**
     * 正文增量的合并窗口（毫秒）。
     *
     * <p>逐 token 下发意味着每 token 一次 servlet 写。合并一小批再发，能显著降低开销，
     * 而 60ms 远低于人能感知的延迟，打字机效果不受影响。</p>
     */
    private long deltaFlushMillis = 60L;

    public boolean isAllowPesticideDosage() {
        return allowPesticideDosage;
    }

    public void setAllowPesticideDosage(boolean allowPesticideDosage) {
        this.allowPesticideDosage = allowPesticideDosage;
    }

    public int getDefaultDays() {
        return defaultDays;
    }

    public void setDefaultDays(int defaultDays) {
        this.defaultDays = defaultDays;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public long getDeltaFlushMillis() {
        return deltaFlushMillis;
    }

    public void setDeltaFlushMillis(long deltaFlushMillis) {
        this.deltaFlushMillis = deltaFlushMillis;
    }
}
