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

    private final DeepSeekProperties properties;
    private final RestTemplate restTemplate;

    public DeepSeekService(DeepSeekProperties properties,
                           @Qualifier("deepSeekRestTemplate") RestTemplate restTemplate) {
        this.properties = properties;
        this.restTemplate = restTemplate;
    }

    @SuppressWarnings("rawtypes")
    public AiChatResponse chat(List<ChatMessage> messages) {
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
            body.put("max_tokens", 1200);

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
        Object messageObject = ((Map) choices.get(0)).get("message");
        if (!(messageObject instanceof Map)) {
            throw new DeepSeekException("AI_UPSTREAM_ERROR", "AI 服务返回格式异常，请稍后重试");
        }
        Object contentObject = ((Map) messageObject).get("content");
        if (!(contentObject instanceof String) || ((String) contentObject).trim().isEmpty()) {
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
