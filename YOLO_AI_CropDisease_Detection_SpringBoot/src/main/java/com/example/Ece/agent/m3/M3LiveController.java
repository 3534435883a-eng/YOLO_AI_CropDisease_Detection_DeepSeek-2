package com.example.Ece.agent.m3;

import com.example.Ece.common.Result;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;

@RestController
@RequestMapping("/m3/live")
public class M3LiveController {
    private final M3LiveService service;
    public M3LiveController(M3LiveService service){this.service=service;}
    public static class StartRequest { public int year=2025; }
    public static class StepRequest { public int expectedCursor; public int stepCount=48; }
    @PostMapping("/start") public Result<?> start(@RequestBody StartRequest request) {
        try{return Result.success(service.start(request.year));}
        catch(IOException|IllegalArgumentException e){return Result.error("M3_LIVE_START_FAILED",e.getMessage());}
    }
    @GetMapping("/{id}") public Result<?> current(@PathVariable String id,@RequestParam(defaultValue="false") boolean compact) {
        try{return Result.success(service.current(id,compact));}catch(IOException e){return Result.error("M3_LIVE_UNAVAILABLE",e.getMessage());}
    }
    @PostMapping("/{id}/step") public Result<?> step(@PathVariable String id,@RequestBody StepRequest request) {
        try{return Result.success(service.step(id,request.expectedCursor,request.stepCount));}
        catch(IOException|IllegalStateException e){return Result.error("M3_LIVE_STEP_FAILED",e.getMessage());}
    }
    @DeleteMapping("/{id}") public Result<?> delete(@PathVariable String id){service.delete(id);return Result.success(null);}
    @PostMapping("/{id}/scenario/{operation}") public Result<?> scenario(@PathVariable String id,@PathVariable String operation,
            @RequestBody com.fasterxml.jackson.databind.JsonNode input){
        try{return Result.success(service.scenarioCommand(id,operation,input));}
        catch(IOException|IllegalArgumentException|IllegalStateException e){return Result.error("M3_SCENARIO_FAILED",e.getMessage());}
    }
}
