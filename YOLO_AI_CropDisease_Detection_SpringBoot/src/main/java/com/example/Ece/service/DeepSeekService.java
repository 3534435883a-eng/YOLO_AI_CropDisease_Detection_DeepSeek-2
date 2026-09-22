package com.example.Ece.service;

import com.example.Ece.config.DeepSeekProperties;
import com.example.Ece.dto.ai.AiChatResponse;
import com.example.Ece.dto.ai.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class DeepSeekService {
    private static final Logger LOGGER = LoggerFactory.getLogger(DeepSeekService.class);
    private static final int MAX_MESSAGES = 30;
    private static final int MAX_CONTENT_LENGTH = 12_000;
    private static final List<String> ALLOWED_ROLES = Arrays.asList("system", "user", "assistant");
    private static final int LEGACY_MAX_TOKENS = 1200;
    private static final int PLANNING_MAX_TOKENS = 600;
    private static final int COMPOSING_PLAIN_MAX_TOKENS = 3000;
    private static final int COMPOSING_MAX_TOKENS = 8000;

    /**
     * 单次调用的可选参数。
     *
     * <p>思考模式按官方 API 默认是**开启**的，但并非所有调用都该开：</p>
     * <ul>
     *   <li><b>规划步</b>关闭思考：只需要吐一个严格 JSON 动作，思考既拖慢每一步往返，
     *       又会与正文争夺 {@code max_tokens} 输出预算（推理占满时 {@code content} 会是空的）。</li>
     *   <li><b>作答步</b>开启思考并放宽预算：要组织带引用的中文长答，值得多花推理。</li>
     * </ul>
     *
     * <p><b>兼容性约束</b>：{@code thinking} 为 {@code null} 时**完全不发送该字段**，
     * 使既有调用方（旧版问答接口）的请求体逐字节不变——上游若不认识该字段会直接 400。</p>
     */
    public static final class ChatOptions {

        private final Boolean thinking;
        private final int maxTokens;

        private ChatOptions(Boolean thinking, int maxTokens) {
            this.thinking = thinking;
            this.maxTokens = maxTokens;
        }

        /** 规划步：关闭思考、收紧输出预算。 */
        public static ChatOptions planning() {
            return new ChatOptions(Boolean.FALSE, PLANNING_MAX_TOKENS);
        }

        /** 作答步：开启思考、放宽输出预算。 */
        public static ChatOptions composing() {
            return new ChatOptions(Boolean.TRUE, COMPOSING_MAX_TOKENS);
        }

        /**
         * 作答步的退路：**关闭思考**。
         *
         * <p>实测（2026-09-23，deepseek-flash）：思考模式下作答 20s 以上时，推理可能吃光输出预算，
         * 返回 {@code finish_reason=length} 且 {@code content} 为空（报 AI_OUTPUT_TRUNCATED）。
         * 关掉思考后没有推理占用预算，正文一定拿得到——用质量换可用性，且只在必要时才走这条路。</p>
         */
        public static ChatOptions composingWithoutThinking() {
            return new ChatOptions(Boolean.FALSE, COMPOSING_PLAIN_MAX_TOKENS);
        }

        /** 既有行为：不发送 thinking 字段、沿用原 token 上限。 */
        public static ChatOptions legacy() {
            return new ChatOptions(null, LEGACY_MAX_TOKENS);
        }
    }

    private final DeepSeekProperties properties;
    private final RestTemplate restTemplate;

    public DeepSeekService(DeepSeekProperties properties,
                           @Qualifier("deepSeekRestTemplate") RestTemplate restTemplate) {
        this.properties = properties;
        this.restTemplate = restTemplate;
    }

    @SuppressWarnings("rawtypes")
    public AiChatResponse chat(List<ChatMessage> messages) {
        return chat(messages, ChatOptions.legacy());
    }

    @SuppressWarnings("rawtypes")
    public AiChatResponse chat(List<ChatMessage> messages, ChatOptions options) {
        ChatOptions actualOptions = options == null ? ChatOptions.legacy() : options;
        String requestId = UUID.randomUUID().toString();
        long startedAt = System.currentTimeMillis();
        try {
            if (!properties.hasApiKey()) {
                throw new DeepSeekException("AI_NOT_CONFIGURED", "AI 服务尚未配置，请联系管理员设置 API Key");
            }
            validateMessages(messages);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey().trim());

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", properties.getModel());
            body.put("messages", messages);
            body.put("stream", false);
            body.put("max_tokens", Integer.valueOf(actualOptions.maxTokens));
            if (actualOptions.thinking != null) {
                Map<String, Object> thinking = new LinkedHashMap<String, Object>();
                thinking.put("type", actualOptions.thinking.booleanValue() ? "enabled" : "disabled");
                body.put("thinking", thinking);
            }

            String upstreamUrl = normalizedBaseUrl() + "/chat/completions";
            ResponseEntity<Map> response = restTemplate.exchange(
                    upstreamUrl,
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    Map.class
            );
            AiChatResponse result = parseResponse(response.getBody());
            logOutcome(requestId, "AI_SUCCESS", startedAt);
            return result;
        } catch (DeepSeekException error) {
            logOutcome(requestId, error.getCode(), startedAt);
            throw error;
        } catch (HttpStatusCodeException error) {
            DeepSeekException mapped = mapHttpError(error);
            logOutcome(requestId, mapped.getCode(), startedAt);
            throw mapped;
        } catch (ResourceAccessException error) {
            DeepSeekException mapped = new DeepSeekException("AI_NETWORK_ERROR", "AI 服务连接超时，请稍后重试");
            logOutcome(requestId, mapped.getCode(), startedAt);
            throw mapped;
        } catch (RestClientException error) {
            DeepSeekException mapped = new DeepSeekException("AI_UPSTREAM_ERROR", "AI 服务暂时不可用，请稍后重试");
            logOutcome(requestId, mapped.getCode(), startedAt);
            throw mapped;
        }
    }

    private void validateMessages(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            throw new DeepSeekException("AI_INVALID_REQUEST", "请提供至少一条 AI 消息");
        }
        if (messages.size() > MAX_MESSAGES) {
            throw new DeepSeekException("AI_INVALID_REQUEST", "AI 消息数量不能超过 30 条");
        }
        for (ChatMessage message : messages) {
            if (message == null || message.getRole() == null || !ALLOWED_ROLES.contains(message.getRole())) {
                throw new DeepSeekException("AI_INVALID_REQUEST", "AI 消息角色不合法");
            }
            String content = message.getContent();
            if (content == null || content.trim().isEmpty()) {
                throw new DeepSeekException("AI_INVALID_REQUEST", "AI 消息内容不能为空");
            }
            if (content.length() > MAX_CONTENT_LENGTH) {
                throw new DeepSeekException("AI_INVALID_REQUEST", "单条 AI 消息不能超过 12000 个字符");
            }
        }
    }

    @SuppressWarnings("rawtypes")
    private AiChatResponse parseResponse(Map response) {
        if (response == null || !(response.get("choices") instanceof List)) {
            throw new DeepSeekException("AI_UPSTREAM_ERROR", "AI 服务返回格式异常，请稍后重试");
        }
        List choices = (List) response.get("choices");
        if (choices.isEmpty() || !(choices.get(0) instanceof Map)) {
            throw new DeepSeekException("AI_UPSTREAM_ERROR", "AI 服务返回格式异常，请稍后重试");
        }
        Map choice = (Map) choices.get(0);
        Object messageObject = choice.get("message");
        if (!(messageObject instanceof Map)) {
            throw new DeepSeekException("AI_UPSTREAM_ERROR", "AI 服务返回格式异常，请稍后重试");
        }
        Map message = (Map) messageObject;
        Object contentObject = message.get("content");
        if (!(contentObject instanceof String) || ((String) contentObject).trim().isEmpty()) {
            // 空 content 有两种常见成因，必须与"上游格式异常"分开报，否则会把"被截断"误导成"服务坏了"：
            // 1) finish_reason=length：输出预算被耗尽；
            // 2) 只回了 reasoning_content：思考模式把预算全花在推理上，正文没来得及输出。
            boolean truncated = "length".equals(choice.get("finish_reason"));
            Object reasoning = message.get("reasoning_content");
            boolean reasoningOnly = reasoning instanceof String && !((String) reasoning).trim().isEmpty();
            if (truncated || reasoningOnly) {
                throw new DeepSeekException("AI_OUTPUT_TRUNCATED",
                        "AI 输出被截断（推理占用了输出预算），请提高 max_tokens 或关闭思考模式");
            }
            throw new DeepSeekException("AI_UPSTREAM_ERROR", "AI 服务返回格式异常，请稍后重试");
        }
        Object modelObject = response.get("model");
        String model = modelObject instanceof String ? (String) modelObject : properties.getModel();
        return new AiChatResponse((String) contentObject, model);
    }

    private DeepSeekException mapHttpError(HttpStatusCodeException error) {
        int status = error.getRawStatusCode();
        if (status == 401 || status == 403) {
            return new DeepSeekException("AI_UPSTREAM_AUTH", "AI 服务认证失败，请检查服务器配置");
        }
        if (status == 429) {
            return new DeepSeekException("AI_RATE_LIMIT", "AI 服务请求过于频繁，请稍后重试");
        }
        return new DeepSeekException("AI_UPSTREAM_ERROR", "AI 服务暂时不可用，请稍后重试");
    }

    private String normalizedBaseUrl() {
        String baseUrl = properties.getBaseUrl() == null ? "" : properties.getBaseUrl().trim();
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        if (baseUrl.isEmpty()) {
            throw new DeepSeekException("AI_NOT_CONFIGURED", "AI 服务地址尚未配置，请联系管理员");
        }
        return baseUrl;
    }

    private void logOutcome(String requestId, String outcome, long startedAt) {
        LOGGER.info("DeepSeek requestId={} outcome={} durationMs={}", requestId, outcome,
                System.currentTimeMillis() - startedAt);
    }
}
