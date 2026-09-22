package com.example.Ece.agent.controller;

import com.alibaba.fastjson.JSON;
import com.example.Ece.agent.dto.AgentChatRequest;
import com.example.Ece.agent.orchestrator.AgentOrchestrator;
import com.example.Ece.agent.orchestrator.AgentResult;
import com.example.Ece.agent.orchestrator.AgentStepEvent;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.annotation.Resource;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** 智能体会话入口：SSE 逐步推送 step / final / error 事件。 */
@RestController
@RequestMapping("/ai/agent")
public class AgentChatController {

    @Resource
    private AgentOrchestrator agentOrchestrator;

    @PostMapping(value = "/chat", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter chat(@RequestBody(required = false) AgentChatRequest request) {
        final AgentChatRequest actual = request == null ? new AgentChatRequest() : request;
        final String sessionId = actual.getSessionId() == null || actual.getSessionId().trim().isEmpty()
                ? UUID.randomUUID().toString() : actual.getSessionId();
        final String question = actual.getQuestion() == null ? "" : actual.getQuestion();
        final String crop = actual.getCrop();
        final SseEmitter emitter = new SseEmitter(Long.valueOf(AgentOrchestrator.TOTAL_TIMEOUT_MS + 5000L));

        Runnable worker = new Runnable() {
            public void run() {
                try {
                    Consumer<AgentStepEvent> sink = new Consumer<AgentStepEvent>() {
                        public void accept(AgentStepEvent event) {
                            try {
                                emitter.send(SseEmitter.event()
                                        .name(event.getType())
                                        .data(JSON.toJSONString(toPayload(event)), MediaType.APPLICATION_JSON));
                            } catch (Exception ignored) {
                                // 客户端断开时忽略，编排层继续完成后落库审计
                            }
                        }
                    };
                    AgentResult result = agentOrchestrator.run(sessionId, question, crop, sink);
                    if (result.getStatus() == AgentResult.Status.ERROR) {
                        emitter.send(SseEmitter.event().name("error")
                                .data("{\"message\":\"智能体执行失败\"}", MediaType.APPLICATION_JSON));
                    }
                    emitter.complete();
                } catch (Exception error) {
                    try {
                        emitter.send(SseEmitter.event().name("error")
                                .data("{\"message\":\"智能体会话异常中断\"}", MediaType.APPLICATION_JSON));
                    } catch (Exception ignored) {
                        // 已断开
                    }
                    emitter.completeWithError(error);
                }
            }
        };
        Thread thread = new Thread(worker, "agent-chat-" + sessionId);
        thread.setDaemon(true);
        thread.start();
        return emitter;
    }

    private Map<String, Object> toPayload(AgentStepEvent event) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("type", event.getType());
        payload.put("stepNo", Integer.valueOf(event.getStepNo()));
        payload.put("toolName", event.getToolName());
        payload.put("message", event.getMessage());
        payload.put("data", event.getPayload());
        return payload;
    }
}
