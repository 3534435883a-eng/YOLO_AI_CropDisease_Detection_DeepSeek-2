package com.example.Ece.agent.controller;

import com.example.Ece.agent.dto.AgentExplanationRequest;
import com.example.Ece.agent.dto.CreateAgentRunRequest;
import com.example.Ece.agent.dto.ManualDeviceRequest;
import com.example.Ece.agent.dto.DeviceHealthRequest;
import com.example.Ece.agent.dto.VisionImportRequest;
import com.example.Ece.agent.service.AgentRunService;
import com.example.Ece.common.Result;
import org.springframework.dao.DataAccessException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/** HTTP boundary for the simulated tomato-greenhouse agent. */
@RestController
@RequestMapping("/agent")
public class AgentController {
    @Resource
    private AgentRunService agentRunService;

    @GetMapping("/runs/active")
    public Result<?> active() {
        return Result.success(agentRunService.getActiveRun());
    }

    @PostMapping("/runs")
    public Result<?> create(@RequestBody(required = false) CreateAgentRunRequest request) {
        return Result.success(agentRunService.createRun(request));
    }

    @GetMapping("/runs/{runId}/summary")
    public Result<?> summary(@PathVariable Long runId) {
        return Result.success(agentRunService.getSummary(runId));
    }

    @GetMapping("/runs/{runId}/comparison")
    public Result<?> comparison(@PathVariable Long runId) {
        return Result.success(agentRunService.getComparison(runId));
    }

    @PostMapping("/runs/{runId}/start")
    public Result<?> start(@PathVariable Long runId,
                           @RequestHeader(value = "X-Actor", required = false) String actor) {
        return Result.success(agentRunService.startRun(runId, actor));
    }

    @PostMapping("/runs/{runId}/pause")
    public Result<?> pause(@PathVariable Long runId,
                           @RequestHeader(value = "X-Actor", required = false) String actor) {
        return Result.success(agentRunService.pauseRun(runId, actor));
    }

    @PostMapping("/runs/{runId}/step")
    public Result<?> step(@PathVariable Long runId,
                          @RequestHeader(value = "X-Actor", required = false) String actor) {
        return Result.success(agentRunService.stepRun(runId, actor));
    }

    @PostMapping("/runs/{runId}/reset")
    public Result<?> reset(@PathVariable Long runId,
                           @RequestHeader(value = "X-Actor", required = false) String actor) {
        return Result.success(agentRunService.resetRun(runId, actor));
    }

    @PostMapping("/runs/{runId}/replay")
    public Result<?> replay(@PathVariable Long runId,
                            @RequestHeader(value = "X-Actor", required = false) String actor) {
        return Result.success(agentRunService.replayRun(runId, actor));
    }

    @PostMapping("/runs/{runId}/devices/{deviceCode}/manual")
    public Result<?> manualDevice(@PathVariable Long runId, @PathVariable String deviceCode,
                                  @RequestBody(required = false) ManualDeviceRequest request) {
        return Result.success(agentRunService.setManualDevice(runId, deviceCode, request));
    }

    @PostMapping("/runs/{runId}/devices/{deviceCode}/health")
    public Result<?> deviceHealth(@PathVariable Long runId, @PathVariable String deviceCode,
                                  @RequestBody(required = false) DeviceHealthRequest request) {
        return Result.success(agentRunService.setDeviceHealth(runId, deviceCode, request));
    }

    @PostMapping("/runs/{runId}/vision-events")
    public Result<?> importVision(@PathVariable Long runId, @RequestBody VisionImportRequest request) {
        return Result.success(agentRunService.importVision(runId, request));
    }

    /** 读取最近的识别事件（含知识库映射结论），供前端展示与"附带识别结果"进对话使用。 */
    @GetMapping("/runs/{runId}/vision-events")
    public Result<?> visionEvents(@PathVariable Long runId,
                                  @RequestParam(value = "limit", required = false, defaultValue = "5") int limit) {
        return Result.success(agentRunService.listVisionEvents(runId, limit));
    }

    @PostMapping("/runs/{runId}/explanation")
    public Result<?> explanation(@PathVariable Long runId,
                                @RequestBody(required = false) AgentExplanationRequest request) {
        return Result.success(agentRunService.explain(runId, request));
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public Result<?> handleAgentInput(RuntimeException error) {
        return Result.error("AGENT_INVALID", error.getMessage());
    }

    @ExceptionHandler(DataAccessException.class)
    public Result<?> handleStorage(DataAccessException error) {
        return Result.error("AGENT_STORAGE_ERROR", "智能体数据表不可用或数据库连接失败，请先执行独立迁移");
    }
}
