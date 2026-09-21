package com.example.Ece.dto.ai;

import java.util.List;

public class AiChatRequest {
    private List<ChatMessage> messages;

    public List<ChatMessage> getMessages() {
        return messages;
    }

    public void setMessages(List<ChatMessage> messages) {
        this.messages = messages;
    }
}
