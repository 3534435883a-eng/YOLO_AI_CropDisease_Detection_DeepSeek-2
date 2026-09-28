package com.example.Ece.service;

/**
 * AI 调用失败。
 *
 * <p>错误码是**对外契约**：推演通道据 {@link #CODE_OUTPUT_TRUNCATED} 与
 * {@link #CODE_STREAM_UNAVAILABLE} 决定降级路径，前端据它们显示不同文案。
 * 因此码值定义成常量，避免同一串字面量散落在判断处与抛出处而悄悄写岔。</p>
 */
public class DeepSeekException extends RuntimeException {

    /** 服务端没有配置 API Key。 */
    public static final String CODE_NOT_CONFIGURED = "AI_NOT_CONFIGURED";

    /** 请求本身不合法（消息为空、超条数、角色非法、单条过长）。 */
    public static final String CODE_INVALID_REQUEST = "AI_INVALID_REQUEST";

    /** 输出被截断（预算耗尽），调用方应提高 max_tokens 或关闭思考模式后重试。 */
    public static final String CODE_OUTPUT_TRUNCATED = "AI_OUTPUT_TRUNCATED";

    /** 流式通道未接线。调用方应退回非流式，并如实标注。 */
    public static final String CODE_STREAM_UNAVAILABLE = "AI_STREAM_UNAVAILABLE";

    public static final String CODE_NETWORK_ERROR = "AI_NETWORK_ERROR";

    public static final String CODE_UPSTREAM_AUTH = "AI_UPSTREAM_AUTH";

    public static final String CODE_RATE_LIMIT = "AI_RATE_LIMIT";

    public static final String CODE_UPSTREAM_ERROR = "AI_UPSTREAM_ERROR";

    private final String code;

    public DeepSeekException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
