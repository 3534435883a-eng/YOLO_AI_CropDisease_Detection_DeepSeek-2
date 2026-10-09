package com.example.Ece.agent.m3.scenario;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ScenarioReviewTimeTest {
    @Test void correctsStepConversionWithoutChangingReviewConditions() {
        assertEquals("2步后（60模拟分钟）查叶面；4步后（120模拟分钟）查根区。", ScenarioAiService.normalizeReviewTime("两步后（约30分钟后）查叶面；4步后（2小时）查根区。"));
    }
    @Test void preservesIndependentReviewWindowsAndNonDurationParentheses() {
        String check = "4小时湿润窗口内查病叶；两步后（温度低于29°C）检查设备。";
        assertEquals(check, ScenarioAiService.normalizeReviewTime(check));
    }
}
