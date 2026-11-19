package com.y271727uy.computatio.algorithm.cycle;

/** Cooperative cancellation polled by long-running planning work. Port of NeoECO's {@code ECOCancellation}. */
@FunctionalInterface
public interface Cancellation {
    Cancellation NONE = () -> {};

    void checkpoint() throws InterruptedException;

    /** Search for an executable order first; optional improvements run on a separate budget. */
    default boolean improveCompletedPlans() { return true; }

    static Cancellation firstSolution(Cancellation cancellation) {
        return new Cancellation() {
            @Override public void checkpoint() throws InterruptedException { cancellation.checkpoint(); }
            @Override public boolean improveCompletedPlans() { return false; }
        };
    }
}
