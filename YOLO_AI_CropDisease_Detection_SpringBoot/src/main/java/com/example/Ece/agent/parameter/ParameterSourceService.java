package com.example.Ece.agent.parameter;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 参数出处登记服务：把 {@link ParameterRegistry} 反射枚举的结果写入
 * {@code agent_parameter_source}，并对外提供查询与统计。
 *
 * <p>这条链路此前完全没接：表建好了（还带 {@code source_url} 字段，与知识库同一套出处纪律），
 * 但**零 Java 引用、零行数据**（2026-09-26 核实）。于是 60 余个仿真参数只能以代码注释里的
 * "三类文献族、待核对出处"存在，不可查询、不可统计，也就无人推进。
 * 本服务把它变成**可查询的缺口清单**。</p>
 */
@Service
public class ParameterSourceService {

    private final ParameterRegistry registry;
    private final ParameterSourceRepository repository;

    public ParameterSourceService(ParameterRegistry registry, ParameterSourceRepository repository) {
        this.registry = registry;
        this.repository = repository;
    }

    /**
     * 重新登记：反射枚举当前源码里的参数，**先清空本版本再重建**。
     *
     * <p>为什么是重建而不是纯 upsert：登记表是快照，不是事件日志。
     * 只 upsert 会留下陈旧行——参数被删、编码格式变更、或上次刷新中途失败，
     * 残留行都会继续出现在列表里，且因为版本号没变既不会被覆盖也不会被清理。</p>
     *
     * @return 本次登记的条数
     */
    public int refresh() {
        List<ParameterSource> sources = registry.enumerate();
        repository.deleteByVersion(ParameterRegistry.REGISTRY_VERSION);
        for (ParameterSource source : sources) {
            repository.upsert(source);
        }
        // 清掉旧版本残留：登记版本递增后，旧版本不该继续出现在列表里。
        repository.deleteOtherVersions(ParameterRegistry.REGISTRY_VERSION);
        return sources.size();
    }

    /** 当前版本的登记列表；status 为 null 时返回全部。 */
    public List<ParameterSource> list(ParameterProvenance.Status status) {
        List<ParameterSource> all = repository.listByVersion(ParameterRegistry.REGISTRY_VERSION);
        if (status == null) {
            return all;
        }
        List<ParameterSource> filtered = new ArrayList<ParameterSource>();
        for (ParameterSource source : all) {
            if (source.getStatus() == status) {
                filtered.add(source);
            }
        }
        return filtered;
    }

    /**
     * 缺口统计。**这是本服务最该被看见的产出**：把"哪些参数不可引用"变成一个数字。
     *
     * <p>同时如实给出两项已知不完整之处：{@code unitMissing}（单位在源码注释里，反射取不到，
     * 未在登记中声明）与 {@code uncoveredNote}（仍是内联字面量、尚未提取为常量的参数，登记表无从枚举）。</p>
     */
    public Map<String, Object> summary() {
        List<ParameterSource> all = list(null);
        Map<ParameterProvenance.Status, Integer> byStatus =
                new EnumMap<ParameterProvenance.Status, Integer>(ParameterProvenance.Status.class);
        for (ParameterProvenance.Status status : ParameterProvenance.Status.values()) {
            byStatus.put(status, Integer.valueOf(0));
        }
        int unitMissing = 0;
        for (ParameterSource source : all) {
            Integer current = byStatus.get(source.getStatus());
            byStatus.put(source.getStatus(), Integer.valueOf(current == null ? 1 : current.intValue() + 1));
            if (source.getUnit() == null || source.getUnit().trim().isEmpty()) {
                unitMissing++;
            }
        }
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("version", ParameterRegistry.REGISTRY_VERSION);
        summary.put("total", Integer.valueOf(all.size()));
        Map<String, Object> statusCounts = new LinkedHashMap<String, Object>();
        for (Map.Entry<ParameterProvenance.Status, Integer> entry : byStatus.entrySet()) {
            statusCounts.put(entry.getKey().name(), entry.getValue());
        }
        summary.put("byStatus", statusCounts);
        summary.put("citable", byStatus.get(ParameterProvenance.Status.VERIFIED));
        summary.put("unitMissing", Integer.valueOf(unitMissing));
        summary.put("uncoveredNote", ParameterRegistry.UNCOVERED_NOTE);
        return summary;
    }
}
