package com.example.Ece.agent.controller;

import com.example.Ece.agent.agri.AgriEnvironmentObservation;
import com.example.Ece.agent.agri.AgriEnvironmentService;
import com.example.Ece.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 当地农情环境接口：实时捕捉当地数据，取不到时退回**演示用模拟地**并如实标明来源。
 *
 * <p>来源字段 {@code source} 是必读项：{@code OBSERVED} 表示真的取到了当地实时数据，
 * {@code SIMULATED_LOCATION} 表示这是演示用固定档案。二者混同就是把模拟冒充实测。</p>
 */
@RestController
@RequestMapping("/ai/agri/environment")
public class AgriEnvironmentController {

    private final AgriEnvironmentService service;

    public AgriEnvironmentController(AgriEnvironmentService service) {
        this.service = service;
    }

    /** 当前当地环境。实时源可用且可达时返回实时观测，否则返回模拟地并说明原因。 */
    @GetMapping("/current")
    public Result<?> current() {
        return Result.success(payload(service.capture()));
    }

    private Map<String, Object> payload(AgriEnvironmentObservation observation) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("location", observation.getLocation());
        data.put("temperatureC", Double.valueOf(observation.getTemperatureC()));
        data.put("humidityPct", Double.valueOf(observation.getHumidityPct()));
        data.put("condition", observation.getCondition());
        data.put("wind", observation.getWind());
        data.put("observedAt", observation.getObservedAt());
        data.put("source", observation.getSource().name());
        data.put("sourceLabel", observation.getSource().getLabel());
        data.put("sourceName", observation.getSourceName());
        data.put("note", observation.getNote());
        // 明确交代没取到什么，避免调用方以为"字段缺失=数值为 0"
        data.put("unavailableFields", java.util.Collections.singletonList(
                "光照/PPFD：天气数据源不提供，本接口不编造该字段"));
        return data;
    }
}
