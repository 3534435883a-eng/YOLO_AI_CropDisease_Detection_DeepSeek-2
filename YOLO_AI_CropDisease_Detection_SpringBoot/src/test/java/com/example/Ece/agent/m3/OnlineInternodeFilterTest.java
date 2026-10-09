package com.example.Ece.agent.m3;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OnlineInternodeFilterTest {
    private OnlineInternodeFilter filter() { return new OnlineInternodeFilter(60, 35, 7); }

    @Test
    void restoresExactStateAndContinuesWithSamePredictionAndCorrection() {
        OnlineInternodeFilter original = filter();
        original.predict(14, 1, false); original.update(63);
        OnlineInternodeFilter restored = filter(); restored.restore(original.age(), original.variance());
        assertEquals(original.height(), restored.height(), 1e-12);
        original.predict(11, 1, true); restored.predict(11, 1, true);
        OnlineInternodeFilter.Update a = original.update(68), b = restored.update(68);
        assertEquals(a.after, b.after, 1e-12);
        assertEquals(a.gain, b.gain, 1e-12);
        assertEquals(original.age(), restored.age(), 1e-12);
        assertEquals(original.variance(), restored.variance(), 1e-12);
    }

    @Test
    void estimatedWeatherInflatesProcessUncertaintyAndCorrectionMovesTowardObservation() {
        OnlineInternodeFilter measured = filter(), estimated = filter();
        measured.predict(10, 1, false); estimated.predict(10, 1, true);
        assertEquals(measured.height(), estimated.height(), 1e-12);
        assertTrue(estimated.variance() > measured.variance());
        double observed = measured.height() + 10;
        OnlineInternodeFilter.Update update = measured.update(observed);
        assertTrue(update.after > update.before);
        assertTrue(Math.abs(observed - update.after) < Math.abs(update.innovation));
        assertTrue(update.measurementWeight > 0 && update.measurementWeight < 1);
        assertTrue(measured.variance() >= 0 && Double.isFinite(measured.variance()));
    }

    @Test
    void rejectsInvalidParametersWithoutEnteringUnboundedInternodeLoop() {
        for (double p : new double[]{0, -1, 0.1, Double.NaN, Double.POSITIVE_INFINITY})
            assertThrows(IllegalArgumentException.class, () -> new OnlineInternodeFilter(60, p, 7));
        for (double length : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY})
            assertThrows(IllegalArgumentException.class, () -> new OnlineInternodeFilter(60, 35, length));
        for (double height : new double[]{-1, Double.NaN, Double.POSITIVE_INFINITY, 1e15})
            assertThrows(IllegalArgumentException.class, () -> new OnlineInternodeFilter(height, 35, 7));
    }

    @Test
    void invalidPredictUpdateAndRestoreKeepLastValidState() {
        OnlineInternodeFilter value = filter();
        double age = value.age(), variance = value.variance();
        for (double invalid : new double[]{-1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> value.predict(invalid, 1, false));
            assertThrows(IllegalArgumentException.class, () -> value.predict(1, invalid, false));
            assertThrows(IllegalArgumentException.class, () -> value.update(invalid));
            assertThrows(IllegalArgumentException.class, () -> value.restore(invalid, variance));
            assertThrows(IllegalArgumentException.class, () -> value.restore(age, invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> value.predict(1e308, 1, false));
        assertThrows(IllegalArgumentException.class, () -> value.update(1e308));
        assertThrows(IllegalArgumentException.class, () -> value.restore(32001, variance));
        assertEquals(age, value.age(), 0);
        assertEquals(variance, value.variance(), 0);
    }

    @Test
    void zeroDurationPredictionAndZeroVarianceRestoreAreValid() {
        OnlineInternodeFilter value = filter();
        double age = value.age(), variance = value.variance();
        value.predict(0, 0, false);
        assertEquals(age, value.age(), 0); assertEquals(variance, value.variance(), 0);
        value.restore(age, 0);
        assertEquals(0, value.variance(), 0);
        assertEquals(0, value.update(value.height() + 1).gain, 0);
    }
}
