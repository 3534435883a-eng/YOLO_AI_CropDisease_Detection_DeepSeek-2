package com.example.Ece.agent.plan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 一次推演的终态。既用于下发 {@code final} 事件，也用于落库。 */
public class DeductionResult {

    /** 流式传输方式，会如实下发给前端。 */
    public enum StreamMode {
        /** 真 token 流式。 */
        STREAM,
        /** 上游流式失败（或未接线）后退回整段返回，用户应知道这不是正常的流式体验。 */
        FALLBACK
    }

    private final String markdown;
    private final boolean bannerInjected;
    private final String structuredJson;
    private final List<String> sections;
    private final List<String> missingFields;
    private final AgriPlanBaseline.Baseline baseline;
    private final StreamMode streamMode;
    private final String fallbackReason;
    private final long elapsedMillis;

    public DeductionResult(String markdown, boolean bannerInjected, String structuredJson,
                           List<String> sections, List<String> missingFields,
                           AgriPlanBaseline.Baseline baseline, StreamMode streamMode,
                           String fallbackReason, long elapsedMillis) {
        this.markdown = markdown;
        this.bannerInjected = bannerInjected;
        this.structuredJson = structuredJson;
        this.sections = sections == null ? new ArrayList<String>() : sections;
        this.missingFields = missingFields == null ? new ArrayList<String>() : missingFields;
        this.baseline = baseline;
        this.streamMode = streamMode;
        this.fallbackReason = fallbackReason;
        this.elapsedMillis = elapsedMillis;
    }

    public String getMarkdown() { return markdown; }

    public boolean isBannerInjected() { return bannerInjected; }

    /** 结构化动作清单的原始 JSON；模型没给出可解析结果时为 {@code null}。 */
    public String getStructuredJson() { return structuredJson; }

    public List<String> getSections() { return Collections.unmodifiableList(sections); }

    public List<String> getMissingFields() { return Collections.unmodifiableList(missingFields); }

    public AgriPlanBaseline.Baseline getBaseline() { return baseline; }

    public StreamMode getStreamMode() { return streamMode; }

    /** 降级原因；正常流式时为 {@code null}。 */
    public String getFallbackReason() { return fallbackReason; }

    public long getElapsedMillis() { return elapsedMillis; }
}
