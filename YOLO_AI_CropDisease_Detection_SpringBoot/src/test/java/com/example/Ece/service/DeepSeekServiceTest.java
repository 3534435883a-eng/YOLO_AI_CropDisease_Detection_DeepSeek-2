package com.example.Ece.service;

import com.example.Ece.config.DeepSeekProperties;
import com.example.Ece.dto.ai.AiChatResponse;
import com.example.Ece.dto.ai.ChatMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DeepSeekServiceTest {

    @Test
    void rejectsMissingApiKeyBeforeCallingNetwork() {
        DeepSeekProperties properties = propertiesWithKey("");
        RestTemplate restTemplate = mock(RestTemplate.class);
        DeepSeekService service = new DeepSeekService(properties, restTemplate);

        DeepSeekException error = assertThrows(DeepSeekException.class,
                () -> service.chat(singleUserMessage("你好")));

        assertEquals("AI_NOT_CONFIGURED", error.getCode());
        verifyNoInteractions(restTemplate);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void mapsSuccessfulUpstreamResponseToContentAndModel() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity(response("病害防治建议", "deepseek-flash"), HttpStatus.OK));
        DeepSeekService service = new DeepSeekService(propertiesWithKey("server-side-key"), restTemplate);

        AiChatResponse result = service.chat(singleUserMessage("玉米锈病如何处理？"));

        assertEquals("病害防治建议", result.getContent());
        assertEquals("deepseek-flash", result.getModel());
        ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(eq("https://api.deepseek.com/chat/completions"), eq(HttpMethod.POST),
                entityCaptor.capture(), eq(Map.class));
        assertEquals("Bearer server-side-key", entityCaptor.getValue().getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
    }

    @Test
    void rejectsAnUnsupportedMessageRole() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        DeepSeekService service = new DeepSeekService(propertiesWithKey("server-side-key"), restTemplate);

        DeepSeekException error = assertThrows(DeepSeekException.class,
                () -> service.chat(Collections.singletonList(message("tool", "未允许的角色"))));

        assertEquals("AI_INVALID_REQUEST", error.getCode());
        verifyNoInteractions(restTemplate);
    }

    @Test
    void rejectsMoreThanThirtyMessages() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        DeepSeekService service = new DeepSeekService(propertiesWithKey("server-side-key"), restTemplate);
        List<ChatMessage> messages = new ArrayList<>();
        for (int index = 0; index < 31; index++) {
            messages.add(message("user", "消息" + index));
        }

        DeepSeekException error = assertThrows(DeepSeekException.class, () -> service.chat(messages));

        assertEquals("AI_INVALID_REQUEST", error.getCode());
        verifyNoInteractions(restTemplate);
    }

    @Test
    void rejectsContentLongerThanTwelveThousandCharacters() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        DeepSeekService service = new DeepSeekService(propertiesWithKey("server-side-key"), restTemplate);
        StringBuilder content = new StringBuilder();
        for (int index = 0; index < 12_001; index++) {
            content.append('a');
        }

        DeepSeekException error = assertThrows(DeepSeekException.class,
                () -> service.chat(singleUserMessage(content.toString())));

        assertEquals("AI_INVALID_REQUEST", error.getCode());
        verifyNoInteractions(restTemplate);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void mapsUpstreamAuthenticationFailureToSafeMessage() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.UNAUTHORIZED));
        DeepSeekService service = new DeepSeekService(propertiesWithKey("server-side-key"), restTemplate);

        DeepSeekException error = assertThrows(DeepSeekException.class,
                () -> service.chat(singleUserMessage("测试认证处理")));

        assertEquals("AI_UPSTREAM_AUTH", error.getCode());
        assertEquals("AI 服务认证失败，请检查服务器配置", error.getMessage());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void mapsUpstreamRateLimitToSafeMessage() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS));
        DeepSeekService service = new DeepSeekService(propertiesWithKey("server-side-key"), restTemplate);

        DeepSeekException error = assertThrows(DeepSeekException.class,
                () -> service.chat(singleUserMessage("测试限流处理")));

        assertEquals("AI_RATE_LIMIT", error.getCode());
        assertEquals("AI 服务请求过于频繁，请稍后重试", error.getMessage());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void mapsNetworkFailureToSafeMessage() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(new ResourceAccessException("network unavailable"));
        DeepSeekService service = new DeepSeekService(propertiesWithKey("server-side-key"), restTemplate);

        DeepSeekException error = assertThrows(DeepSeekException.class,
                () -> service.chat(singleUserMessage("测试网络处理")));

        assertEquals("AI_NETWORK_ERROR", error.getCode());
        assertEquals("AI 服务连接超时，请稍后重试", error.getMessage());
    }

    private static DeepSeekProperties propertiesWithKey(String apiKey) {
        DeepSeekProperties properties = new DeepSeekProperties();
        properties.setApiKey(apiKey);
        return properties;
    }

    private static List<ChatMessage> singleUserMessage(String content) {
        return Collections.singletonList(message("user", content));
    }

    private static ChatMessage message(String role, String content) {
        ChatMessage message = new ChatMessage();
        message.setRole(role);
        message.setContent(content);
        return message;
    }

    private static Map<String, Object> response(String content, String model) {
        Map<String, Object> message = new HashMap<>();
        message.put("content", content);
        Map<String, Object> choice = new HashMap<>();
        choice.put("message", message);
        Map<String, Object> response = new HashMap<>();
        response.put("choices", Arrays.asList(choice));
        response.put("model", model);
        return response;
    }
}
