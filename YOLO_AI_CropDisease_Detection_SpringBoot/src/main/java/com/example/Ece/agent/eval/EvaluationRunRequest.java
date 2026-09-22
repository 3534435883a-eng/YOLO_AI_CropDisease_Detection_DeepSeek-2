package com.example.Ece.agent.eval;

/** 评测运行请求。 */
public class EvaluationRunRequest {

    private String batchId;
    private Long seed;
    private Integer days;

    public String getBatchId() { return batchId; }

    public void setBatchId(String batchId) { this.batchId = batchId; }

    public Long getSeed() { return seed; }

    public void setSeed(Long seed) { this.seed = seed; }

    public Integer getDays() { return days; }

    public void setDays(Integer days) { this.days = days; }
}
