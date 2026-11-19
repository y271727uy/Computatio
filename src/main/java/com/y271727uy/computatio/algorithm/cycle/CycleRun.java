package com.y271727uy.computatio.algorithm.cycle;

import com.y271727uy.computatio.algorithm.amount.PlannerAmount;

/**
 * Compact execution metadata, port of NeoECO's {@code PatternRun}: fire {@code transition} {@code count} times per lap.
 * The final run repeats the preceding {@code repeatWidth} runs (itself included) {@code repetitions} times in total.
 * Circuits are non-nested and preserve physical order.
 */
public record CycleRun(int transition, PlannerAmount count, int repeatWidth, PlannerAmount plannerRepetitions)
        implements RepeatLayout.Run {
    public CycleRun(int transition, PlannerAmount count) { this(transition, count, 1, PlannerAmount.ONE); }

    public CycleRun {
        if (transition < 0) throw new IllegalArgumentException("A run must name a transition");
        if (count == null || count.signum() < 0) throw new IllegalArgumentException("A run count must not be negative");
        if (plannerRepetitions == null || repeatWidth < 1 || plannerRepetitions.signum() <= 0
                || plannerRepetitions.equals(PlannerAmount.ONE) && repeatWidth != 1) {
            throw new IllegalArgumentException("Invalid circuit repeat");
        }
    }

    /** Compatibility view; planning and execution accounting use the exact fields. */
    @Override public long repetitions() { return plannerRepetitions.longValueSaturated(); }

    public boolean repeated() { return plannerRepetitions.compareTo(PlannerAmount.ONE) > 0; }
}
