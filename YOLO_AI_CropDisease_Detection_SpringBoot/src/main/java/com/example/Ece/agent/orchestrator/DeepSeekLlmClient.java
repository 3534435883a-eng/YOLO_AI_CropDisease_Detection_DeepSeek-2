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
            "\n【本步要求】依据上文工具返回的证据组织中文回答：先结论、再依据与[编号]引用、最后风险与注意事项；"
                    + "涉及药剂必须提示遵循当地登记与用药规范并说明需人工确认；证据不足时明确说明依据不足，不得臆造。"
                    // 2026-09-27 放开：证据与问题主题明显不一致时（例如问的是甲病、检索回来的全是乙病），
                    // 允许补充模型自身掌握的通用农艺知识——但**必须单独成段并原样标注**，
                    // 让用户一眼看出哪部分没有本项目知识库依据。放开的是"能不能帮上忙"，
                    // 不是"能不能把通用经验混在引用里冒充有出处的结论"。
                    + "\n【例外】若上文证据与问题主题**明显不一致**（问 A 病、证据全是 B 病），"
                    + "不要用近似主题硬凑结论；可以另起一段补充你自身掌握的通用农艺知识，"
                    + "该段第一行必须原样写出：" + AgentOrchestrator.GENERAL_KNOWLEDGE_BANNER
                    + "，并注明需人工确认；该段内不得给出具体药剂剂量或安全间隔期。";

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
