package com.example.Ece.agent.orchestrator;

import com.example.Ece.dto.ai.AiChatResponse;
import com.example.Ece.dto.ai.ChatMessage;
import com.example.Ece.service.DeepSeekException;
import com.example.Ece.service.DeepSeekService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 把编排层接到既有的 DeepSeek 后端代理上；API Key 仍只从环境变量读取。 */
@Component
public class DeepSeekLlmClient implements LlmClient {

    private static final String PLAN_HINT =
            "\n【本步要求】只输出下一步动作 JSON：{\"tool\":\"...\",\"input\":{...}} 或 {\"tool\":\"FINALIZE\",\"input\":{}}。";
    private static final String COMPOSE_HINT =
            "\n【本步要求】直接回应用户问题，用自然中文和按需Markdown排版，不套固定标题或固定尾注。"
                    + "使用实际工具数据解释当前情况，真正支持结论的资料才用[编号]引用。没有资料也可给一般解释，"
                    + "但具体未核实的数值或结论要说明条件，不能编造来源、现场数据或执行结果。"
                    + "资料与主题不一致时不要强行引用。执行结果以服务端工具状态为准，分析中不能说已经解决。"
                    + "仅具体药剂处方需要登记、标签和使用条件；一般问题不添加用药声明。";

    private final DeepSeekService deepSeekService;

    public DeepSeekLlmClient(DeepSeekService deepSeekService) {
        this.deepSeekService = deepSeekService;
    }

    /**
     * 规划步：**关闭思考模式**。
     *
     * <p>规划只需吐一个严格 JSON 动作。实测（deepseek-flash，2026-09-23）关掉思考后单步 955ms、
     * content 恰好是纯 JSON；而开启思考时推理会与正文争夺 {@code max_tokens}——max_tokens=50 时
     * 实测 {@code finish_reason=length}、{@code content} 为空、{@code reasoning_content} 249 字，
     * 整轮会退化成不可解析或被截断。</p>
     */
    public String plan(List<Map<String, Object>> history) {
        return call(withHint(history, PLAN_HINT), DeepSeekService.ChatOptions.planning());
    }

    /**
     * 作答步：**开启思考模式**并放宽输出预算——要组织带引用、含风险提示的中文长答。
     *
     * <p>实测思考模式可能把输出预算全部消耗在推理上，返回空正文（AI_OUTPUT_TRUNCATED）。
     * 这种失败重试一次**关闭思考**即可拿到正文；其他错误原样抛出，由编排层按失败处理。</p>
     */
    public String compose(List<Map<String, Object>> history) {
        try {
            return call(withHint(history, COMPOSE_HINT), DeepSeekService.ChatOptions.composing());
        } catch (DeepSeekException error) {
            if (!"AI_OUTPUT_TRUNCATED".equals(error.getCode())) {
                throw error;
            }
            return call(withHint(history, COMPOSE_HINT), DeepSeekService.ChatOptions.composingWithoutThinking());
        }
    }

    private List<Map<String, Object>> withHint(List<Map<String, Object>> history, String hint) {
        List<Map<String, Object>> copy = new ArrayList<Map<String, Object>>(history);
        if (copy.isEmpty()) {
            return copy;
        }
        int lastIndex = copy.size() - 1;
        Map<String, Object> last = copy.get(lastIndex);
        Map<String, Object> hinted = new LinkedHashMap<String, Object>(last);
        hinted.put("content", String.valueOf(last.get("content")) + hint);
        copy.set(lastIndex, hinted);
        return copy;
    }

    private String call(List<Map<String, Object>> history, DeepSeekService.ChatOptions options) {
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        for (Map<String, Object> entry : history) {
            ChatMessage message = new ChatMessage();
            message.setRole(String.valueOf(entry.get("role")));
            message.setContent(String.valueOf(entry.get("content")));
            messages.add(message);
        }
        AiChatResponse response = deepSeekService.chat(messages, options);
        return response == null ? null : response.getContent();
    }
}
