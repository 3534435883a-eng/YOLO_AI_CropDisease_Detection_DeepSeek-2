package com.example.Ece.dto.ai;

public class AiChatResponse {
    private final String content;
    private final String model;

    public AiChatResponse(String content, String model) {
        this.content = content;
        this.model = model;
    }

    public String getContent() {
        return content;
    }

    public String getModel() {
        return model;
    }
}
