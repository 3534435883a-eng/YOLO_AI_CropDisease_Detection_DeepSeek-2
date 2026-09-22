package com.example.Ece.agent.eval;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/** 一批四档对照的评测结果。 */
public class EvaluationBatch {

    private final String batchId;
    private final long seed;
    private final int days;
    private final long elapsedMillis;
    private final Map<EvaluationStrategy, EvaluationOutcome> outcomes;

    public EvaluationBatch(String batchId, long seed, int days, long elapsedMillis,
                           Map<EvaluationStrategy, EvaluationOutcome> outcomes) {
        this.batchId = batchId;
        this.seed = seed;
        this.days = days;
        this.elapsedMillis = elapsedMillis;
        this.outcomes = outcomes == null
                ? new EnumMap<EvaluationStrategy, EvaluationOutcome>(EvaluationStrategy.class)
                : new EnumMap<EvaluationStrategy, EvaluationOutcome>(outcomes);
    }

    public String getBatchId() { return batchId; }

    public long getSeed() { return seed; }

    public int getDays() { return days; }

    public long getElapsedMillis() { return elapsedMillis; }

    public Map<EvaluationStrategy, EvaluationOutcome> getOutcomes() {
        return Collections.unmodifiableMap(outcomes);
    }
}
