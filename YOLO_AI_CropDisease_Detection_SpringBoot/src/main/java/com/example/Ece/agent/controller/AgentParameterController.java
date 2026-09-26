package com.example.Ece.agent.controller;

import com.example.Ece.agent.parameter.ParameterProvenance;
import com.example.Ece.agent.parameter.ParameterSource;
import com.example.Ece.agent.parameter.ParameterSourceService;
import com.example.Ece.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 仿真参数出处接口。
 *
 * <p>用途：查"这个参数能不能对外引用"。缺口清单此前埋在代码注释里（"三类文献族、待核对出处"），
 * 现在可查询、可统计。</p>
 */
@RestController
@RequestMapping("/ai/agent/parameters")
public class AgentParameterController {

    private final ParameterSourceService service;

    public AgentParameterController(ParameterSourceService service) {
        this.service = service;
    }

    /**
     * 缺口统计——最该被先看的接口：一眼看出有多少参数不可引用。
     */
    @GetMapping("/summary")
    public Result<?> summary() {
        return Result.success(service.summary());
    }

    /**
     * 登记列表。
     *
     * @param status 可选，按出处性质过滤：VERIFIED / UNVERIFIED_LITERATURE / PLACEHOLDER
     */
    @GetMapping
    public Result<?> list(@RequestParam(value = "status", required = false) String status) {
        ParameterProvenance.Status parsed = null;
        if (status != null && !status.trim().isEmpty()) {
            try {
                parsed = ParameterProvenance.Status.valueOf(status.trim().toUpperCase());
            } catch (IllegalArgumentException error) {
                return Result.error("PARAMETER_STATUS_INVALID",
                        "未知的出处性质：" + status + "，可选 VERIFIED / UNVERIFIED_LITERATURE / PLACEHOLDER");
            }
        }
        return Result.success(rows(service.list(parsed)));
    }

    /**
     * 按当前源码重新登记（反射枚举后 upsert）。
     *
     * <p>幂等：表上有 {@code (parameter_code, version)} 唯一键，重复调用只更新不重复。
     * 参数值或出处变化时应先递增 {@code ParameterRegistry.REGISTRY_VERSION} 再刷新，
     * 使登记结果与代码版本对应——否则引用登记结果时无法复现。</p>
     */
    @PostMapping("/refresh")
    public Result<?> refresh() {
        int count = service.refresh();
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("registered", Integer.valueOf(count));
        payload.put("summary", service.summary());
        return Result.success(payload);
    }

    private List<Map<String, Object>> rows(List<ParameterSource> sources) {
        List<Map<String, Object>> payload = new ArrayList<Map<String, Object>>();
        for (ParameterSource source : sources) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("code", source.getCode());
            item.put("name", source.getName());
            item.put("value", source.getValueText());
            item.put("unit", source.getUnit());
            item.put("status", source.getStatus().name());
            item.put("statusLabel", source.getStatus().getLabel());
            item.put("citable", Boolean.valueOf(source.getStatus() == ParameterProvenance.Status.VERIFIED));
            item.put("sourceName", source.getSourceName());
            item.put("sourceUrl", source.getSourceUrl());
            item.put("version", source.getVersion());
            payload.add(item);
        }
        return payload;
    }
}
