package com.example.Ece.agent.controller;

import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** String message conversion reads the stream's content type, so set its charset before the first frame. */
final class Utf8SseEmitter extends SseEmitter {
    Utf8SseEmitter(Long timeout) { super(timeout); }
    @Override protected void extendResponse(ServerHttpResponse response) {
        super.extendResponse(response);
        response.getHeaders().setContentType(new MediaType("text", "event-stream", java.nio.charset.StandardCharsets.UTF_8));
    }
}
