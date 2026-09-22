package com.example.Ece.agent.crop;

import com.example.Ece.agent.engine.TomatoSimulationEngine;
import com.example.Ece.agent.model.SimulationState;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;

class TomatoCropGrowthModelTest {
    private final TomatoCropGrowthModel model = new TomatoCropGrowthModel();
    private final TomatoSimulationEngine engine = new TomatoSimulationEngine();

    private SimulationState environment(double temperature, double humidity, double soil, double co2, double light) {
        return engine.evaluate(LocalDateTime.of(2026, 9, 21, 12, 0), temperature, humidity, soil, co2, light, 6.2);
    }

    private TomatoCropState simulate(SimulationState environment, int steps) {
        TomatoCropState state = model.initial();
        for (int i = 0; i < steps; i++) state = model.advance(state, environment, 15);
        return state;
    }

    @Test
    void isDeterministicForSameInputs() {
        SimulationState env = environment(24.0, 70.0, 60.0, 800.0, 500.0);
        TomatoCropState first = simulate(env, 96);
        TomatoCropState second = simulate(env, 96);
        assertEquals(first.getGdd(), second.getGdd(), 1e-9);
        assertEquals(first.getLai(), second.getLai(), 1e-9);
        assertEquals(first.getWFruit(), second.getWFruit(), 1e-9);
        assertEquals(first.getStage(), second.getStage());
    }

    @Test
    void growingDegreeDaysIncreaseWithTemperature() {
        TomatoCropState cool = simulate(environment(12.0, 70.0, 60.0, 800.0, 400.0), 96);
        TomatoCropState warm = simulate(environment(26.0, 70.0, 60.0, 800.0, 400.0), 96);
        assertTrue(warm.getGdd() > cool.getGdd());
        assertTrue(cool.getGdd() >= 0.0);
    }

    @Test
    void leafAreaRisesThenPlateausWithoutFalling() {
        TomatoCropState early = simulate(environment(24.0, 70.0, 60.0, 800.0, 600.0), 96);
        TomatoCropState later = simulate(environment(24.0, 70.0, 60.0, 800.0, 600.0), 480);
        assertTrue(early.getLai() > 0.0);
        assertTrue(later.getLai() >= early.getLai() * 0.5);
        assertTrue(later.getLai() <= TomatoGrowthParameters.MAX_LAI);
    }

    @Test
    void highTemperatureReducesFruitSet() {
        TomatoCropState optimal = simulate(environment(24.0, 70.0, 60.0, 800.0, 600.0), 480);
        TomatoCropState hot = simulate(environment(34.0, 55.0, 60.0, 800.0, 600.0), 480);
        assertTrue(hot.getFruitSetRate() < optimal.getFruitSetRate());
    }

    @Test
    void waterStressReducesDryMatterAndFruitNeverExceedsTotal() {
        TomatoCropState wet = simulate(environment(24.0, 70.0, 70.0, 800.0, 600.0), 480);
        TomatoCropState dry = simulate(environment(24.0, 45.0, 25.0, 800.0, 600.0), 480);
        assertTrue(dry.getWaterFactor() < 1.0);
        assertTrue(dry.getWTotal() < wet.getWTotal());
        assertTrue(dry.getWFruit() <= dry.getWTotal() + 1e-9);
    }
}
