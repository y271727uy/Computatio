package com.y271727uy.computatio.api.cycle;

import com.y271727uy.computatio.algorithm.cycle.Cancellation;
import com.y271727uy.computatio.algorithm.cycle.PlanningBudget;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * One calculation's shared allowance of checkpoints and time. Every checkpoint of the caller and of the solver counts
 * against the same budget, and the solver carves its optional stages out of what remains, exactly as NeoECO's planning
 * budget does. Running out throws {@link CycleBudgetExhaustedException}.
 */
public final class CycleBudget implements CycleCheck {
    public static final long DEFAULT_MAX_WORK = PlanningBudget.DEFAULT_MAX_WORK;
    public static final long DEFAULT_MAX_MILLIS = PlanningBudget.DEFAULT_MAX_MILLIS;

    private final PlanningBudget budget;

    private CycleBudget(PlanningBudget budget) {
        this.budget = budget;
    }

    /** {@link #DEFAULT_MAX_WORK} checkpoints and {@link #DEFAULT_MAX_MILLIS} milliseconds of wall time. */
    public static CycleBudget withDefaults(CycleCheck check) {
        return new CycleBudget(new PlanningBudget(cancellation(check)));
    }

    /** At most {@code maxWork} checkpoints and {@code maxNanos} nanoseconds measured by {@code clock}. */
    public static CycleBudget of(CycleCheck check, long maxWork, long maxNanos, LongSupplier clock) {
        Objects.requireNonNull(clock, "clock");
        return new CycleBudget(new PlanningBudget(cancellation(check), maxWork, maxNanos, clock));
    }

    @Override
    public void checkpoint() throws InterruptedException {
        try {
            budget.checkpoint();
        } catch (PlanningBudget.Exhausted exhausted) {
            throw CycleBudgetExhaustedException.of(exhausted);
        }
    }

    @Override
    public boolean improveCompletedPlans() {
        return budget.improveCompletedPlans();
    }

    /** Starts the optional-improvement window once: at most 50 ms after the first plan, within the first 200 ms. */
    public void beginOptimization() {
        budget.beginOptimization();
    }

    /** Checkpoints spent so far. */
    public long work() {
        return budget.work();
    }

    /** A nested allowance of at most half of what remains, so exhausting it leaves this budget usable. */
    public CycleBudget slice(long maxSliceWork, long maxSliceNanos) {
        return new CycleBudget(budget.slice(maxSliceWork, maxSliceNanos));
    }

    PlanningBudget internal() {
        return budget;
    }

    private static Cancellation cancellation(CycleCheck check) {
        return Bridge.cancellation(check);
    }
}
