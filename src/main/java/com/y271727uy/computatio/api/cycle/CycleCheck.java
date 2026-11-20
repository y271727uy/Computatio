package com.y271727uy.computatio.api.cycle;

/**
 * Cooperative cancellation polled by long-running cycle work. Throw {@link InterruptedException} to stop.
 * Pass a {@link CycleBudget} instead when the work must share one allowance with the caller's own planning. A check
 * that polls an allowance of its own throws {@link CycleBudgetExhaustedException} when that runs out.
 */
@FunctionalInterface
public interface CycleCheck {
    CycleCheck NONE = () -> { };

    void checkpoint() throws InterruptedException;

    /** False asks the solver to stop at the first executable plan instead of improving it. */
    default boolean improveCompletedPlans() {
        return true;
    }
}
