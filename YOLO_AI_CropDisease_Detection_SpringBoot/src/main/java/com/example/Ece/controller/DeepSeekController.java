package com.example.Ece.controller;

import com.example.Ece.common.Result;
import com.example.Ece.dto.ai.AiChatRequest;
import com.example.Ece.service.DeepSeekException;
import com.example.Ece.service.DeepSeekService;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/ai")
public class DeepSeekController {
    private final DeepSeekService deepSeekService;

    public DeepSeekController(DeepSeekService deepSeekService) {
        this.deepSeekService = deepSeekService;
    }

    @PostMapping("/chat")
    public Result<?> chat(@RequestBody(required = false) AiChatRequest request) {
        try {
            return Result.success(deepSeekService.chat(request == null ? null : request.getMessages()));
        } catch (DeepSeekException error) {
            return Result.error(error.getCode(), error.getMessage());
        }
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<?> handleMalformedRequest() {
        return Result.error("AI_INVALID_REQUEST", "AI 请求格式不正确");
    }
}
