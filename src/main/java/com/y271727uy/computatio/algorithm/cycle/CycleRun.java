package com.y271727uy.computatio.algorithm.cycle;

import com.y271727uy.computatio.algorithm.amount.PlannerAmount;

/**
 * One step of a compact execution plan: fire {@code transition} {@code count} times.
 *
 * <p>Repeated circuits are flat and never nested. The last run of a circuit carries {@code repeatWidth}, the number
 * of runs in the circuit including itself, and {@code repetitions}, the total lap count; every other run has
 * width 1 and one repetition. This is the same layout as {@link RepeatLayout}, with exact repetition counts.
 */
public record CycleRun(int transition, PlannerAmount count, int repeatWidth, PlannerAmount repetitions) {
    public CycleRun {
        if (transition < 0) throw new IllegalArgumentException("Transition must not be negative");
        if (count == null || count.signum() <= 0) throw new IllegalArgumentException("Run count must be positive");
        if (repeatWidth < 1 || repetitions == null || repetitions.signum() <= 0
                || repetitions.equals(PlannerAmount.ONE) && repeatWidth != 1) {
            throw new IllegalArgumentException("Invalid recipe circuit repeat");
        }
    }

    public CycleRun(int transition, PlannerAmount count) {
        this(transition, count, 1, PlannerAmount.ONE);
    }

    public boolean repeated() {
        return repetitions.compareTo(PlannerAmount.ONE) > 0;
    }
}
