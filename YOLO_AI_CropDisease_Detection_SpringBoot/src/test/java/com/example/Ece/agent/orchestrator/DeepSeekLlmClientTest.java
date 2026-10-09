package com.example.Ece.agent.orchestrator;

import com.example.Ece.dto.ai.AiChatResponse;
import com.example.Ece.dto.ai.ChatMessage;
import com.example.Ece.service.DeepSeekService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DeepSeekLlmClientTest {
    @Test void preservesSystemAndLatestQuestionWithinServiceLimitsWithoutMutatingHistory() {
        DeepSeekService service = mock(DeepSeekService.class);
        when(service.chat(any(),any())).thenReturn(new AiChatResponse("test-model","{}"));
        List<Map<String,Object>> history = new ArrayList<>();
        Map<String,Object> system = new LinkedHashMap<>(); system.put("role","system"); system.put("content","保护当前任务与来源边界"); history.add(system);
        for(int i=0;i<35;i++) { Map<String,Object> msg=new LinkedHashMap<>(); msg.put("role","user"); msg.put("content","earlier-"+i); history.add(msg); }
        char[] large=new char[13000]; Arrays.fill(large,'文');
        Map<String,Object> last=new LinkedHashMap<>(); last.put("role","user"); last.put("content",new String(large)); history.add(last);
        new DeepSeekLlmClient(service).plan(history);
        ArgumentCaptor<List> captured=ArgumentCaptor.forClass(List.class);
        verify(service).chat(captured.capture(),any());
        List<ChatMessage> messages=captured.getValue();
        assertEquals(30,messages.size()); assertEquals("system",messages.get(0).getRole());
        assertTrue(messages.get(29).getContent().contains("本步要求"));
        for(ChatMessage message:messages) assertTrue(message.getContent().length()<=12000);
        assertEquals(37,history.size()); assertEquals(13000,history.get(36).get("content").toString().length());
    }
}
