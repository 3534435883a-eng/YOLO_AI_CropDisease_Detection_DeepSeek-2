package com.example.Ece.agent.eval;

import com.example.Ece.agent.engine.TomatoDecisionPolicy;
import com.example.Ece.agent.engine.TomatoSimulationEngine;
import com.example.Ece.agent.model.AgentDeviceCodes;
import com.example.Ece.agent.model.DecisionPlan;
import com.example.Ece.agent.model.DeviceCommand;
import com.example.Ece.agent.model.SimulationState;
import com.example.Ece.agent.profile.HortiM3Profile;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 资源记账与物理的一致性。
 *
 * <p>起因：2026-09-26 审计发现评测侧的滴灌水费按 600 L/步计，而物理侧浇的是 60 L/步
 * （`TomatoSimulationEngine.IRRIGATION_L_PER_TICK = 60.0`），相差 10 倍，
 * 该差额直接进入成本与利润。四处（物理 / 规则层声明 / 孪生运行时 / 文档正文）对一处，
 * 判定记账侧为错误并统一为 60 L。</p>
 *
 * <p>本测试**锁住这个等式**：只要有人改动其中一侧而忘了另一侧，构建就会失败，
 * 而不是像这次一样让错误悄悄流进已发布的数字。</p>
 */
class ResourceAccountingTest {

    @Test
    void irrigationAccountingMatchesThePhysics() {
        double accountedLiters = ResourceRates.IRRIGATION_M3_PER_STEP * 1000.0;
        assertEquals(TomatoSimulationEngine.IRRIGATION_L_PER_TICK, accountedLiters, 1e-9,
                "记账侧的单步滴灌水量与物理侧不一致："
                        + "记账 " + accountedLiters + " L vs 物理 "
                        + TomatoSimulationEngine.IRRIGATION_L_PER_TICK + " L");
    }

    @Test
    void irrigationAccountingMatchesTheAmountTheRuleLayerDeclares() {
        // 让规则层决定灌溉：土壤水分低于 45% 会触发小水滴灌
        TomatoSimulationEngine engine = new TomatoSimulationEngine();
        SimulationState dry = engine.evaluate(LocalDateTime.of(2026, 9, 21, 9, 0),
                25.0, 65.0, 40.0, 700.0, 500.0, 6.5);
        DecisionPlan plan = new TomatoDecisionPolicy().decide(dry);

        DeviceCommand irrigation = null;
        for (DeviceCommand command : plan.getCommands()) {
            if (AgentDeviceCodes.IRRIGATION.equals(command.getDeviceCode())) {
                irrigation = command;
            }
        }
        assertNotNull(irrigation, "土壤水分 40% 应触发灌溉");
        assertTrue(irrigation.isTargetOn(), "土壤水分 40% 应触发灌溉");
        assertNotNull(irrigation.getResourceAmount(), "灌溉指令必须声明水量");

        // Rule prescriptions declare m3, and the default decision lasts one
        // M3 30-minute tick; PER_STEP rates describe 15 reference minutes.
        double declaredLiters = irrigation.getResourceAmount().doubleValue() * 1000.0;
        double accountedLiters = ResourceRates.IRRIGATION_M3_PER_STEP * 1000.0
                * HortiM3Profile.AGENT_TICK_MINUTES / ResourceRates.REFERENCE_MINUTES;
        assertEquals(declaredLiters, accountedLiters, 1e-9,
                "规则层声明的滴灌水量与评测记账不一致：声明 " + declaredLiters
                        + " L vs 记账 " + accountedLiters + " L");
        DeviceCommand fifteenMinute = new TomatoDecisionPolicy().decide(dry, ResourceRates.REFERENCE_MINUTES).getCommands()
                .stream().filter(c -> AgentDeviceCodes.IRRIGATION.equals(c.getDeviceCode())).findFirst().get();
        assertEquals(TomatoSimulationEngine.IRRIGATION_L_PER_TICK,
                fifteenMinute.getResourceAmount().doubleValue() * 1000.0, 1e-9,
                "同一15分钟参考时段的规则声明必须与引擎实际注水量一致");
    }

    /**
     * **已知缺口，此处刻意不设断言。**
     *
     * <p>湿帘用水在评测平台完全未折算：数字孪生侧有
     * {@code TomatoSimulationEngine.coolingPadEvaporationLiters(...)} 按进风温湿差动态计算，
     * 评测侧 {@code PerformanceEvaluationService.usageOf} 里根本没有湿帘水这一项。
     * 因此连续控制档（更常用湿帘）的水耗被低估，跨档水耗比较对这一项不公平。</p>
     *
     * <p>不在此处断言的原因：补上它需要把 SimulationState 与 seed 传进 usageOf，
     * 且会再次改变各档水耗数值——属建模决策，需先确认口径。记录在此以免被遗忘。</p>
     */
    @Test
    void coolingPadWaterIsStillNotAccountedInTheEvaluationPlatform() {
        TomatoSimulationEngine engine = new TomatoSimulationEngine();
        SimulationState hot = engine.evaluate(LocalDateTime.of(2026, 9, 21, 14, 0),
                35.0, 55.0, 50.0, 500.0, 800.0, 6.5);
        // 数字孪生侧能算出湿帘补水，说明该量是可算的——只是评测侧没接。
        double makeup = engine.coolingPadEvaporationLiters(hot, 15, 42L);
        assertTrue(makeup > 0.0,
                "湿帘补水在孪生侧应当可算；若此项为 0，说明该模型也变了，需重新核对本缺口");
    }
}
