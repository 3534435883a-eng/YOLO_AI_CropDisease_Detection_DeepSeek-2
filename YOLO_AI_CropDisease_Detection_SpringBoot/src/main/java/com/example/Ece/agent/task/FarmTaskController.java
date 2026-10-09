package com.example.Ece.agent.task;

import com.example.Ece.common.Result;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/** Uses the existing application interceptor and Result envelope; report is a download. */
@RestController
@RequestMapping("/agent/tasks")
public class FarmTaskController {
    private final FarmTaskService service;
    public FarmTaskController(FarmTaskService service) { this.service = service; }
    private interface Operation { Object invoke() throws IOException; }
    private Result<?> perform(Operation action) {
        try { return Result.success(action.invoke()); }
        catch (IllegalArgumentException error) { return Result.error("FARM_TASK_INVALID", error.getMessage()); }
        catch (IOException error) { return Result.error("FARM_TASK_UNAVAILABLE", error.getMessage()); }
    }
    @PostMapping public Result<?> create(@RequestBody JsonNode input) { return perform(() -> service.create(input)); }
    @GetMapping("/{id}") public Result<?> read(@PathVariable String id) { return perform(() -> service.refresh(id)); }
    @PatchMapping("/{id}") public Result<?> patch(@PathVariable String id, @RequestBody JsonNode input) { return perform(() -> service.patch(id, input)); }
    @PostMapping("/{id}/evidence") public Result<?> evidence(@PathVariable String id, @RequestBody JsonNode input) { return perform(() -> service.addEvidence(id, input)); }
    @PostMapping("/{id}/turns") public Result<?> turns(@PathVariable String id, @RequestBody JsonNode input) { return perform(() -> service.addTurn(id, input)); }
    @PostMapping("/{id}/observations") public Result<?> observations(@PathVariable String id, @RequestBody JsonNode input) {
        return perform(() -> { service.addObservation(id, input); return service.refresh(id); });
    }
    @PostMapping("/{id}/actions") public Result<?> actions(@PathVariable String id, @RequestBody JsonNode input) { return perform(() -> service.addAction(id, input)); }
    @PatchMapping("/{id}/actions/{key}") public Result<?> actionStatus(@PathVariable String id, @PathVariable String key, @RequestBody JsonNode input) { return perform(() -> service.setActionStatus(id, key, input)); }
    @GetMapping(value = "/{id}/report", produces = "text/markdown;charset=UTF-8")
    public void report(@PathVariable String id, HttpServletResponse response) throws IOException {
        final String report;
        try { report = service.report(id); }
        catch (IllegalArgumentException error) { response.setStatus(400); response.setContentType("text/plain;charset=UTF-8"); response.getWriter().write(error.getMessage()); return; }
        catch (IOException error) { response.setStatus(404); response.setContentType("text/plain;charset=UTF-8"); response.getWriter().write(error.getMessage()); return; }
        response.setContentType("text/markdown;charset=UTF-8"); response.setCharacterEncoding("UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"farm-task-" + id + ".md\"");
        response.getWriter().write(report);
    }
}
