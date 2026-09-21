package com.example.Ece.controller;

import com.example.Ece.dto.ai.AiChatResponse;
import com.example.Ece.service.DeepSeekException;
import com.example.Ece.service.DeepSeekService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DeepSeekController.class)
class DeepSeekControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DeepSeekService deepSeekService;

    @Test
    void returnsProjectSuccessEnvelope() throws Exception {
        when(deepSeekService.chat(anyList()))
                .thenReturn(new AiChatResponse("环境湿度应降低", "deepseek-flash"));

        mockMvc.perform(post("/ai/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messages\":[{\"role\":\"user\",\"content\":\"给出温室建议\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.content").value("环境湿度应降低"))
                .andExpect(jsonPath("$.data.model").value("deepseek-flash"));
    }

    @Test
    void returnsSafeErrorEnvelope() throws Exception {
        when(deepSeekService.chat(anyList()))
                .thenThrow(new DeepSeekException("AI_NOT_CONFIGURED", "AI 服务尚未配置"));

        mockMvc.perform(post("/ai/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messages\":[{\"role\":\"user\",\"content\":\"测试 AI 配置\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("AI_NOT_CONFIGURED"))
                .andExpect(jsonPath("$.msg").value("AI 服务尚未配置"));
    }
}
