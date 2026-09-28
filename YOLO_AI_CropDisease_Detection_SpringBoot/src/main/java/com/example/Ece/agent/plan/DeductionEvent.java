package com.example.Ece.agent.plan;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 推演过程中推给前端的一个事件。
 *
 * <p>事件类型是**对外协议**的一部分，前端按它分发渲染，改动即为破坏性变更：</p>
 * <ul>
 *   <li>{@code stage} — 阶段进度（"正在计算参考基线"），让用户在等首 token 时知道系统没卡死</li>
 *   <li>{@code baseline} — 机理模型参考基线算好了，结构化数值</li>
 *   <li>{@code delta} — token 增量，{@code reasoning=true} 表示属于思考过程</li>
 *   <li>{@code reset} — <b>丢弃已收到的正文</b>。截断重试时用，否则前端会把半截正文和新正文拼在一起</li>
 *   <li>{@code final} — 终态：正文 + 结构化动作清单 + 标注</li>
 *   <li>{@code error} — 连接层或执行失败</li>
 * </ul>
 */
public class DeductionEvent {

    public static final String TYPE_STAGE = "stage";
    public static final String TYPE_BASELINE = "baseline";
    public static final String TYPE_DELTA = "delta";
    public static final String TYPE_RESET = "reset";
    public static final String TYPE_FINAL = "final";
    public static final String TYPE_ERROR = "error";

    private final String type;
    private final Map<String, Object> payload;

    public DeductionEvent(String type, Map<String, Object> payload) {
        this.type = type;
        this.payload = payload == null
                ? new LinkedHashMap<String, Object>() : new LinkedHashMap<String, Object>(payload);
    }

    public static DeductionEvent stage(String phase, String message) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("phase", phase);
        payload.put("message", message);
        return new DeductionEvent(TYPE_STAGE, payload);
    }

    public String getType() { return type; }

    public Map<String, Object> getPayload() { return Collections.unmodifiableMap(payload); }
}
