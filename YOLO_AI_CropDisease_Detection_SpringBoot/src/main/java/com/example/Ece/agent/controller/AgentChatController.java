package com.example.Ece.agent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** 智能体会话入口：SSE 逐步推送 step / final / error 事件。 */
@RestController
@RequestMapping("/ai/agent")
public class AgentChatController {
    private static final MediaType JSON_UTF8 = new MediaType(MediaType.APPLICATION_JSON, java.nio.charset.StandardCharsets.UTF_8);

    @Resource
    private AgentOrchestrator agentOrchestrator;
    @Resource private ObjectMapper mapper;

    @PostMapping(value = "/chat", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter chat(@RequestBody(required = false) AgentChatRequest request) {
        final AgentChatRequest actual = request == null ? new AgentChatRequest() : request;
        final String sessionId = actual.getSessionId() == null || actual.getSessionId().trim().isEmpty()
                ? UUID.randomUUID().toString() : actual.getSessionId();
        final String question = actual.getQuestion() == null ? "" : actual.getQuestion();
        final String crop = actual.getCrop();
        final SseEmitter emitter = new Utf8SseEmitter(Long.valueOf(AgentOrchestrator.TOTAL_TIMEOUT_MS + 5000L));
        final AtomicBoolean cancelled = new AtomicBoolean();
        final AtomicReference<Thread> workerThread = new AtomicReference<>();
        Runnable cancel = () -> {cancelled.set(true);Thread t=workerThread.get();if(t!=null)t.interrupt();};
        emitter.onTimeout(cancel);
        emitter.onError(error -> cancel.run());
        emitter.onCompletion(cancel);

        Runnable worker = new Runnable() {
            public void run() {
                try {
                    Consumer<AgentStepEvent> sink = new Consumer<AgentStepEvent>() {
                        public void accept(AgentStepEvent event) {
                            if(cancelled.get())return;
                            try {
                                emitter.send(SseEmitter.event()
                                        .name(event.getType())
                                        .data(mapper.writeValueAsString(toPayload(event)), JSON_UTF8));
                            } catch (Exception ignored) {
                                // 停止后不再提交后续工具；已经提交的仿真动作仍按大棚状态显示。
                                cancel.run();
                            }
                        }
                    };
                    agentOrchestrator.run(sessionId, question, crop, actual.getSimulationRunId(), actual.getRunId(),
                            actual.getTaskId(), actual.getRequestId(),
                            !Boolean.FALSE.equals(actual.getAllowSimulationActions()), cancelled::get, sink);
                    emitter.complete();
                } catch (Exception error) {
                    try {
                        emitter.send(SseEmitter.event().name("error")
                                .data(mapper.writeValueAsString(java.util.Collections.singletonMap("message", error instanceof IllegalArgumentException ? error.getMessage() : "智能体会话异常中断")), JSON_UTF8));
                    } catch (Exception ignored) {
                        // 已断开
                    }
                    // The error is already a formatted SSE frame; JSON exception advice cannot write to this stream.
                    emitter.complete();
                }
            }
        };
        Thread thread = new Thread(worker, "agent-chat-" + sessionId);
        workerThread.set(thread);
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
