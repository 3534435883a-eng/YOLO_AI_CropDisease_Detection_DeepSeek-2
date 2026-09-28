package com.example.Ece.agent.dto;

import com.example.Ece.agent.plan.AgriSituationInput;

import java.util.Map;

/** 推演请求体。 */
public class AgriPlanRequest {

    /** 结构化农情；键名见 {@code SituationFields}，未登记的键会被忽略。 */
    private Map<String, Object> situation;

    /** 用户的自然语言诉求，可空。 */
    private String question;

    /** 参考基线种子；空则用默认值。同种子同天数逐值复现。 */
    private Long seed;

    /** 参考基线天数；空或非正数用配置默认值。 */
    private Integer days;

    public Map<String, Object> getSituation() { return situation; }

    public void setSituation(Map<String, Object> situation) { this.situation = situation; }

    public String getQuestion() { return question; }

    public void setQuestion(String question) { this.question = question; }

    public Long getSeed() { return seed; }

    public void setSeed(Long seed) { this.seed = seed; }

    public Integer getDays() { return days; }

    public void setDays(Integer days) { this.days = days; }

    /** 转成结构化输入。未登记键由 {@link AgriSituationInput#put} 忽略。 */
    public AgriSituationInput toSituation() {
        return AgriSituationInput.fromMap(situation);
    }
}
