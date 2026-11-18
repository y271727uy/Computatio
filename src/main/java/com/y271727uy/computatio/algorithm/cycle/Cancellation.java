package com.y271727uy.computatio.algorithm.cycle;

/** Cooperative cancellation polled by long-running planning work. */
@FunctionalInterface
public interface Cancellation {
    Cancellation NONE = () -> {};

    void checkpoint() throws InterruptedException;
}
