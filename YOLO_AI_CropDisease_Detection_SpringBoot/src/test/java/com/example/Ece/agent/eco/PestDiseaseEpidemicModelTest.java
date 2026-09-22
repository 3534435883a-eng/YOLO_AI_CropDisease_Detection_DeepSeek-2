package com.example.Ece.agent.eco;

import com.example.Ece.agent.crop.TomatoCropGrowthModel;
import com.example.Ece.agent.crop.TomatoCropState;
import com.example.Ece.agent.engine.TomatoSimulationEngine;
import com.example.Ece.agent.model.SimulationState;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PestDiseaseEpidemicModel} 的确定性场景测试。
 *
 * <p>重点验证两点：温度/湿度响应的方向性（灰霉病“高湿促发”、白粉病“中等湿度偏好、高湿受抑”）
 * 以及输出限幅与确定性。每个测试各自构造环境，不共享任何可变状态。</p>
 */
class PestDiseaseEpidemicModelTest {

    private static final int STEP_MINUTES = 15;
    private static final int SHORT_RUN_STEPS = 480;
    private static final int LONG_RUN_STEPS = 1440;

    private final PestDiseaseEpidemicModel model = new PestDiseaseEpidemicModel();
    private final TomatoSimulationEngine engine = new TomatoSimulationEngine();
    private final TomatoCropGrowthModel cropModel = new TomatoCropGrowthModel();

    /** 用真实模拟引擎构造环境状态。 */
    private SimulationState environment(double temperatureC, double airHumidityPct) {
        return engine.evaluate(LocalDateTime.of(2026, 6, 18, 6, 0), temperatureC, airHumidityPct,
                60.0, 800.0, 400.0, 6.2);
    }

    /** 从 initial() 出发推进固定步数；作物固定为苗期初始状态。 */
    private DiseaseState simulate(SimulationState environment, int steps) {
        DiseaseState state = model.initial();
        TomatoCropState crop = cropModel.initial();
        for (int i = 0; i < steps; i++) {
            state = model.advance(state, environment, crop, STEP_MINUTES);
        }
        return state;
    }

    @Test
    void humidCoolFavoursBotrytisOverDry() {
        DiseaseState humid = simulate(environment(18.0, 95.0), SHORT_RUN_STEPS);
        DiseaseState dry = simulate(environment(18.0, 45.0), SHORT_RUN_STEPS);
        assertTrue(humid.severity(DiseaseKind.BOTRYTIS) > dry.severity(DiseaseKind.BOTRYTIS),
                "18 ℃ 下高湿（95%）灰霉病严重度应高于干燥（45%），实际 高湿="
                        + humid.severity(DiseaseKind.BOTRYTIS) + " 干燥=" + dry.severity(DiseaseKind.BOTRYTIS));
    }

    @Test
    void powderyMildewPrefersModerateHumidityUnlikeBotrytis() {
        DiseaseState moderate = simulate(environment(22.0, 65.0), SHORT_RUN_STEPS);
        DiseaseState humid = simulate(environment(22.0, 95.0), SHORT_RUN_STEPS);

        assertTrue(moderate.severity(DiseaseKind.POWDERY_MILDEW) >= humid.severity(DiseaseKind.POWDERY_MILDEW),
                "22 ℃ 下中等湿度（65%）白粉病严重度应不低于高湿（95%），实际 中等="
                        + moderate.severity(DiseaseKind.POWDERY_MILDEW)
                        + " 高湿=" + humid.severity(DiseaseKind.POWDERY_MILDEW));

        // 反向断言：灰霉病的湿度响应与白粉病相反。若模型只产生单一单调趋势，这两条不可能同时成立。
        assertTrue(humid.severity(DiseaseKind.BOTRYTIS) > moderate.severity(DiseaseKind.BOTRYTIS),
                "22 ℃ 下高湿（95%）灰霉病严重度应高于中等湿度（65%），实际 高湿="
                        + humid.severity(DiseaseKind.BOTRYTIS)
                        + " 中等=" + moderate.severity(DiseaseKind.BOTRYTIS));
    }

    @Test
    void severityAndDamageStayInRange() {
        DiseaseState state = simulate(environment(20.0, 95.0), LONG_RUN_STEPS);
        for (DiseaseKind kind : DiseaseKind.values()) {
            double severity = state.severity(kind);
            double inoculum = state.inoculum(kind);
            double latent = state.latent(kind);
            assertTrue(severity >= 0.0 && severity <= 100.0, kind + " 严重度越界: " + severity);
            assertTrue(inoculum >= 0.0 && inoculum <= 1.0, kind + " 病原基数越界: " + inoculum);
            assertTrue(latent >= 0.0 && latent <= 1.0, kind + " 潜伏进度越界: " + latent);
        }
        double damage = state.getDiseaseDamageFactor();
        assertTrue(damage >= 0.0 && damage <= 1.0, "损失因子越界: " + damage);
        assertTrue(state.getPestPopulation() >= 0.0, "害虫种群不得为负: " + state.getPestPopulation());
        assertTrue(state.getInfectionEvents() >= 0, "侵染事件次数不得为负");
    }

    @Test
    void isDeterministic() {
        SimulationState environment = environment(20.0, 95.0);
        DiseaseState first = simulate(environment, SHORT_RUN_STEPS);
        DiseaseState second = simulate(environment, SHORT_RUN_STEPS);
        assertEquals(first.getInfectionEvents(), second.getInfectionEvents());
        for (DiseaseKind kind : DiseaseKind.values()) {
            assertEquals(first.severity(kind), second.severity(kind), 1e-9);
            assertEquals(first.inoculum(kind), second.inoculum(kind), 1e-9);
            assertEquals(first.latent(kind), second.latent(kind), 1e-9);
        }
        assertEquals(first.getDiseaseDamageFactor(), second.getDiseaseDamageFactor(), 1e-9);
        assertEquals(first.getPestPopulation(), second.getPestPopulation(), 1e-9);
    }
}
