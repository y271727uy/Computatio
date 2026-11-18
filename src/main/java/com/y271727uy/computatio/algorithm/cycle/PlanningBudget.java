package com.y271727uy.computatio.algorithm.cycle;

import java.util.function.LongSupplier;

/**
 * One calculation's shared allowance of checkpoints and wall time. Exhaustion is distinct from cancellation,
 * internal errors and a proof of missing materials: the result of the interrupted work remains unknown.
 */
public final class PlanningBudget implements Cancellation {
    public static final long DEFAULT_MAX_WORK = 5_000_000L;
    public static final long DEFAULT_MAX_MILLIS = 10_000L;

    private final Cancellation cancellation;
    private final long maxWork;
    private final long maxNanos;
    private final LongSupplier clock;
    private final long started;
    private long work;

    /** Default allowance of {@link #DEFAULT_MAX_WORK} checkpoints and {@link #DEFAULT_MAX_MILLIS} milliseconds. */
    public PlanningBudget(Cancellation cancellation) {
        this(cancellation, DEFAULT_MAX_WORK, DEFAULT_MAX_MILLIS * 1_000_000L, System::nanoTime);
    }

    public PlanningBudget(Cancellation cancellation, long maxWork, long maxNanos, LongSupplier clock) {
        if (maxWork < 1 || maxNanos < 1) throw new IllegalArgumentException("Planning budgets must be positive");
        this.cancellation = cancellation;
        this.maxWork = maxWork;
        this.maxNanos = maxNanos;
        this.clock = clock;
        this.started = clock.getAsLong();
    }

    @Override
    public synchronized void checkpoint() throws InterruptedException {
        cancellation.checkpoint();
        long elapsed = clock.getAsLong() - started;
        if (++work > maxWork || elapsed >= maxNanos) {
            throw new Exhausted("Shared planning allowance exhausted after " + work + " checkpoints; reason="
                + (work > maxWork ? "WORK_LIMIT" : "TIME_LIMIT") + " elapsedMs=" + elapsed / 1_000_000L
                + " maxMillis=" + maxNanos / 1_000_000L + " maxWork=" + maxWork + "; result remains unknown");
        }
    }

    /** Distinct from cancellation, internal errors and a proof of missing materials. */
    public static final class Exhausted extends RuntimeException {
        private Exhausted(String message) { super(message, null, false, false); }
    }
}
