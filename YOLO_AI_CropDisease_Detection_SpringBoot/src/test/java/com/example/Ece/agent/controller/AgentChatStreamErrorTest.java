package com.example.Ece.agent.controller;

import com.example.Ece.agent.orchestrator.AgentOrchestrator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class AgentChatStreamErrorTest {
    @Test void validationFailureCompletesAsSseWithoutSecondJsonExceptionResponse() throws Exception {
        verifyError(new IllegalArgumentException("任务与运行不匹配，请选择同一大棚任务。"), "任务与运行不匹配");
    }
    @Test void unexpectedFailureDoesNotExposeInternalExceptionDetails() throws Exception {
        verifyError(new IllegalStateException("private-debug-detail"), "智能体会话异常中断");
    }
    private void verifyError(RuntimeException failure, String expected) throws Exception {
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        when(orchestrator.run(anyString(), anyString(), nullable(String.class), nullable(String.class), nullable(Long.class),
                nullable(String.class), nullable(String.class), anyBoolean(), any(), any())).thenThrow(failure);
        AgentChatController controller = new AgentChatController();
        ReflectionTestUtils.setField(controller, "agentOrchestrator", orchestrator);
        ReflectionTestUtils.setField(controller, "mapper", new ObjectMapper());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        MvcResult pending = mvc.perform(post("/ai/agent/chat").contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"如何管理\"}")).andReturn();
        pending.getAsyncResult(5000);
        MvcResult result = mvc.perform(asyncDispatch(pending)).andReturn();
        assertEquals(200, result.getResponse().getStatus());
        assertTrue(result.getResponse().getContentType().startsWith("text/event-stream"));
        String content = new String(result.getResponse().getContentAsByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(content.contains("event:error"), content); assertTrue(content.contains(expected), content);
        assertFalse(content.contains("private-debug-detail"));
    }
}
