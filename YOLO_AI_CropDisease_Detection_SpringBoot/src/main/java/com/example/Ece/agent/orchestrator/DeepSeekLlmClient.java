package com.example.Ece.agent.orchestrator;

import com.example.Ece.dto.ai.AiChatResponse;
import com.example.Ece.dto.ai.ChatMessage;
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
            "\n【本步要求】依据上文工具返回的证据组织中文回答：先结论、再依据与[编号]引用、最后风险与注意事项；"
                    + "涉及药剂必须提示遵循当地登记与用药规范并说明需人工确认；证据不足时明确说明依据不足，不得臆造。";

    private final DeepSeekService deepSeekService;

    public DeepSeekLlmClient(DeepSeekService deepSeekService) {
        this.deepSeekService = deepSeekService;
    }

    public String plan(List<Map<String, Object>> history) {
        return call(withHint(history, PLAN_HINT));
    }

    public String compose(List<Map<String, Object>> history) {
        return call(withHint(history, COMPOSE_HINT));
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

    private String call(List<Map<String, Object>> history) {
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        for (Map<String, Object> entry : history) {
            ChatMessage message = new ChatMessage();
            message.setRole(String.valueOf(entry.get("role")));
            message.setContent(String.valueOf(entry.get("content")));
            messages.add(message);
        }
        AiChatResponse response = deepSeekService.chat(messages);
        return response == null ? null : response.getContent();
    }
}
