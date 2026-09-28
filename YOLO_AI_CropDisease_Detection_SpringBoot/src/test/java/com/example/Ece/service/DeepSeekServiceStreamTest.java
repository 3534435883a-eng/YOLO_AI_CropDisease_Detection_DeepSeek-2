package com.example.Ece.service;

import com.example.Ece.config.DeepSeekProperties;
import com.example.Ece.dto.ai.ChatMessage;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.web.client.RequestCallback;
import org.springframework.web.client.ResponseExtractor;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 流式解析的边界测试。
 *
 * <p>这些用例都对着**实测拿到的帧形状**写（2026-09-28 直接 curl {@code deepseek-flash} 抓的真实帧）：
 * {@code reasoning_content} 先于 {@code content}；同一帧里两者可以一个为 {@code null}、另一个有值；
 * 首帧是 {@code {"role":"assistant","content":null,"reasoning_content":""}}。
 * 解析器最容易错的不是"正常帧"，而是这些空字段与畸形帧。</p>
 */
class DeepSeekServiceStreamTest {

    @Test
    void separatesReasoningFromContentAndReturnsContentOnly() {
        List<String> reasoning = new ArrayList<String>();
        List<String> content = new ArrayList<String>();
        String body = frames(
                chunk("{\"content\":null,\"reasoning_content\":\"\"}"),
                chunk("{\"content\":null,\"reasoning_content\":\"先看\"}"),
                chunk("{\"content\":null,\"reasoning_content\":\"棚况。\"}"),
                chunk("{\"content\":\"第一段。\",\"reasoning_content\":null}"),
                chunk("{\"content\":\"第二段。\",\"reasoning_content\":null}"),
                "[DONE]");

        String result = serviceWithStream(body).chatStream(
                singleUserMessage("出个方案"),
                DeepSeekService.ChatOptions.simulating(),
                (text, isReasoning) -> {
                    (isReasoning ? reasoning : content).add(text);
                    return true;
                });

        // 思考与正文必须分得开——首帧那个空 reasoning_content 不该产生一次空回调。
        assertEquals(java.util.Arrays.asList("先看", "棚况。"), reasoning);
        assertEquals(java.util.Arrays.asList("第一段。", "第二段。"), content);
        // 返回值只含正文，思考不混进去。
        assertEquals("第一段。第二段。", result);
    }

    @Test
    void ignoresFramesWithoutUsableFields() {
        List<String> received = new ArrayList<String>();
        String body = frames(
                chunk("{\"content\":null,\"reasoning_content\":null}"),
                chunk("{}"),
                chunk("{\"content\":\"有内容\",\"reasoning_content\":null}"),
                "[DONE]");

        String result = serviceWithStream(body).chatStream(
                singleUserMessage("问题"), null,
                (text, isReasoning) -> {
                    received.add(text);
                    return true;
                });

        assertEquals(Collections.singletonList("有内容"), received);
        assertEquals("有内容", result);
    }

    @Test
    void stopsAtDoneSentinel() {
        List<String> received = new ArrayList<String>();
        String body = frames(
                chunk("{\"content\":\"保留\",\"reasoning_content\":null}"),
                "[DONE]",
                chunk("{\"content\":\"丢弃\",\"reasoning_content\":null}"));

        String result = serviceWithStream(body).chatStream(
                singleUserMessage("问题"), null,
                (text, isReasoning) -> {
                    received.add(text);
                    return true;
                });

        assertEquals("保留", result);
        assertEquals(Collections.singletonList("保留"), received);
    }

    @Test
    void skipsMalformedFrameWithoutDroppingTheRestOfTheStream() {
        String body = frames(
                chunk("{\"content\":\"前半\",\"reasoning_content\":null}"),
                "{这不是合法 JSON",
                chunk("{\"content\":\"后半\",\"reasoning_content\":null}"),
                "[DONE]");

        // 单个畸形帧不该让用户丢掉整段已生成的推演。
        String result = serviceWithStream(body).chatStream(
                singleUserMessage("问题"), null, (text, isReasoning) -> true);

        assertEquals("前半后半", result);
    }

    @Test
    void ignoresCommentAndHeartbeatLines() {
        String body = ": keep-alive\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"正文\",\"reasoning_content\":null}}]}\n\n"
                + ": 又一条心跳\n\n"
                + "data: [DONE]\n\n";

        String result = serviceWithStream(body).chatStream(
                singleUserMessage("问题"), null, (text, isReasoning) -> true);

        assertEquals("正文", result);
    }

    @Test
    void reportsTruncationEvenWhenContentIsNonEmpty() {
        // 半截方案冒充完整方案，是这条链路上最需要避免的失败——所以正文非空也要报截断。
        String body = frames(
                chunk("{\"content\":\"只写了一半的方案…\",\"reasoning_content\":null}"),
                "{\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}");

        DeepSeekException error = assertThrows(DeepSeekException.class,
                () -> serviceWithStream(body).chatStream(
                        singleUserMessage("问题"), null, (text, isReasoning) -> true));

        assertEquals("AI_OUTPUT_TRUNCATED", error.getCode());
    }

    @Test
    void detectsTruncationWhenFinalFrameHasNoDeltaKey() {
        // 有的上游最后一帧干脆不带 delta 键。若把 finish_reason 的检查放在
        // "delta 为空就跳过"之后，这种截断就永远检不出来。
        String body = frames(
                chunk("{\"content\":\"半截\",\"reasoning_content\":null}"),
                "{\"choices\":[{\"finish_reason\":\"length\"}]}");

        DeepSeekException error = assertThrows(DeepSeekException.class,
                () -> serviceWithStream(body).chatStream(
                        singleUserMessage("问题"), null, (text, isReasoning) -> true));

        assertEquals("AI_OUTPUT_TRUNCATED", error.getCode());
    }

    @Test
    void reportsReasoningOnlyStreamAsTruncated() {
        String body = frames(
                chunk("{\"content\":null,\"reasoning_content\":\"想了很久但没写正文\"}"),
                "{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}");

        DeepSeekException error = assertThrows(DeepSeekException.class,
                () -> serviceWithStream(body).chatStream(
                        singleUserMessage("问题"), null, (text, isReasoning) -> true));

        assertEquals("AI_OUTPUT_TRUNCATED", error.getCode());
    }

    @Test
    void reportsEmptyStreamAsUpstreamError() {
        DeepSeekException error = assertThrows(DeepSeekException.class,
                () -> serviceWithStream(frames("[DONE]")).chatStream(
                        singleUserMessage("问题"), null, (text, isReasoning) -> true));

        assertEquals("AI_UPSTREAM_ERROR", error.getCode());
    }

    @Test
    void consumerReturningFalseAbortsTheStream() {
        List<String> received = new ArrayList<String>();
        String body = frames(
                chunk("{\"content\":\"第一段\",\"reasoning_content\":null}"),
                chunk("{\"content\":\"第二段\",\"reasoning_content\":null}"),
                chunk("{\"content\":\"第三段\",\"reasoning_content\":null}"),
                "[DONE]");

        String result = serviceWithStream(body).chatStream(
                singleUserMessage("问题"), null,
                (text, isReasoning) -> {
                    received.add(text);
                    return received.size() < 2;
                });

        // 客户端断开后不该继续把上游读完——那是白花钱。
        assertEquals(java.util.Arrays.asList("第一段", "第二段"), received);
        assertEquals("第一段第二段", result);
    }

    @Test
    void unwiredStreamingReportsExplicitCodeInsteadOfSilentlyDegrading() {
        DeepSeekService service = new DeepSeekService(propertiesWithKey("server-side-key"),
                mock(RestTemplate.class));

        assertFalse(service.isStreamingWired());
        DeepSeekException error = assertThrows(DeepSeekException.class,
                () -> service.chatStream(singleUserMessage("问题"), null, (text, isReasoning) -> true));
        assertEquals("AI_STREAM_UNAVAILABLE", error.getCode());
    }

    @Test
    void sendsStreamFlagAndDisablesGzipNegotiation() throws Exception {
        List<RequestCallback> captured = new ArrayList<RequestCallback>();
        RestTemplate connector = mock(RestTemplate.class);
        when(connector.execute(anyString(), eq(HttpMethod.POST), any(RequestCallback.class),
                any(ResponseExtractor.class)))
                .thenAnswer(invocation -> {
                    captured.add(invocation.getArgument(2));
                    return null;
                });
        DeepSeekService service = new DeepSeekService(propertiesWithKey("server-side-key"),
                mock(RestTemplate.class));
        service.setStreamingRestTemplate(connector);

        try {
            service.chatStream(singleUserMessage("问题"), null, (text, isReasoning) -> true);
        } catch (RuntimeException ignored) {
            // 这里只关心请求体怎么写的，不关心空流被报成什么
        }

        assertEquals(1, captured.size());
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.POST,
                java.net.URI.create("https://api.deepseek.com/chat/completions"));
        captured.get(0).doWithRequest(request);
        String body = new String(request.getBodyAsBytes(), StandardCharsets.UTF_8);

        assertTrue(body.contains("\"stream\":true"), "必须显式开流式，否则拿到的是整段响应：" + body);
        // 一旦上游/中间代理返回 gzip，JDK 的 GZIPInputStream 会预读填充，
        // token 级流就退化成"停顿—一阵突发"，前端的打字机效果会消失。
        assertEquals("identity", request.getHeaders().getFirst(HttpHeaders.ACCEPT_ENCODING));
        assertNotNull(request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
    }

    /* ------------------------------ 辅助 ------------------------------ */

    private DeepSeekService serviceWithStream(String sseBody) {
        RestTemplate streaming = mock(RestTemplate.class);
        when(streaming.execute(anyString(), eq(HttpMethod.POST), any(RequestCallback.class),
                any(ResponseExtractor.class)))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    ResponseExtractor<Void> extractor = invocation.getArgument(3);
                    org.springframework.http.client.ClientHttpResponse response =
                            mock(org.springframework.http.client.ClientHttpResponse.class);
                    when(response.getBody()).thenReturn(
                            new ByteArrayInputStream(sseBody.getBytes(StandardCharsets.UTF_8)));
                    return extractor.extractData(response);
                });
        DeepSeekService service = new DeepSeekService(propertiesWithKey("server-side-key"),
                mock(RestTemplate.class));
        service.setStreamingRestTemplate(streaming);
        return service;
    }

    private String frames(String... parts) {
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.startsWith("data:")) {
                builder.append(part).append("\n\n");
            } else {
                builder.append("data: ").append(part).append("\n\n");
            }
        }
        return builder.toString();
    }

    private String chunk(String deltaJson) {
        return "{\"id\":\"x\",\"choices\":[{\"index\":0,\"delta\":" + deltaJson
                + ",\"finish_reason\":null}]}";
    }

    private DeepSeekProperties propertiesWithKey(String key) {
        DeepSeekProperties properties = new DeepSeekProperties();
        properties.setApiKey(key);
        properties.setBaseUrl("https://api.deepseek.com");
        properties.setModel("deepseek-flash");
        return properties;
    }

    private List<ChatMessage> singleUserMessage(String content) {
        ChatMessage message = new ChatMessage();
        message.setRole("user");
        message.setContent(content);
        return Collections.singletonList(message);
    }
}
