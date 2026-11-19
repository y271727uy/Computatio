package com.y271727uy.computatio.algorithm.cycle;

import java.util.function.LongSupplier;

/**
 * One calculation's shared allowance: recipe discovery, alternate routes, seeds and cycle search. Port of NeoECO's
 * {@code ECOPlanningBudget}; the defaults are NeoECO's configuration defaults (ecoPlanningMaxWork = 50,000,000
 * checkpoints, ecoPlanningMaxMillis = 120,000 ms).
 */
public final class PlanningBudget implements Cancellation {
    public static final long DEFAULT_MAX_WORK = 50_000_000L;
    public static final long DEFAULT_MAX_MILLIS = 120_000L;
    private static final long OPTIMIZATION_MAX_NANOS = 50_000_000L;
    private static final long OPTIMIZATION_TOTAL_NANOS = 200_000_000L;

    private final Cancellation cancellation;
    private final long maxWork;
    private final long maxNanos;
    private final LongSupplier clock;
    private final long started;
    private long work;
    private long optimizationDeadline = Long.MAX_VALUE;

    /** Default allowance of {@link #DEFAULT_MAX_WORK} checkpoints and {@link #DEFAULT_MAX_MILLIS} milliseconds. */
    public PlanningBudget(Cancellation cancellation) {
        this(cancellation, Math.max(1L, DEFAULT_MAX_WORK), Math.max(1L, DEFAULT_MAX_MILLIS) * 1_000_000L, System::nanoTime);
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
    public boolean improveCompletedPlans() { return cancellation.improveCompletedPlans(); }

    @Override
    public synchronized void checkpoint() throws InterruptedException {
        cancellation.checkpoint();
        long elapsed = clock.getAsLong() - started;
        work++;
        if (elapsed >= optimizationDeadline) {
            throw new Exhausted("Optional route optimization window exhausted; reason=OPTIMIZATION_TIME_LIMIT"
                + " elapsedMs=" + elapsed / 1_000_000L + " deadlineMs=" + optimizationDeadline / 1_000_000L
                + "; retaining the validated plan");
        }
        if (work > maxWork || elapsed >= maxNanos) {
            throw new Exhausted("Shared planning allowance exhausted after " + work + " checkpoints; reason="
                + (work > maxWork ? "WORK_LIMIT" : "TIME_LIMIT") + " elapsedMs=" + elapsed / 1_000_000L
                + " maxMillis=" + maxNanos / 1_000_000L + " maxWork=" + maxWork + "; result remains unknown");
        }
    }

    /** Start once: at most 50 ms after the first executable plan, within the session's first 200 ms. */
    public synchronized void beginOptimization() {
        if (optimizationDeadline != Long.MAX_VALUE) return;
        long elapsed = clock.getAsLong() - started;
        optimizationDeadline = Math.min(OPTIMIZATION_TOTAL_NANOS, elapsed + OPTIMIZATION_MAX_NANOS);
    }

    public synchronized long work() { return work; }

    /** Nested allowance for optional searches: never more than half of what remains, so exhausting it leaves this budget usable. */
    public synchronized PlanningBudget slice(long maxSliceWork, long maxSliceNanos) {
        long remainingWork = Math.max(0L, maxWork - work);
        long remainingNanos = Math.max(0L, Math.min(maxNanos, optimizationDeadline) - (clock.getAsLong() - started));
        return new PlanningBudget(this, Math.max(1L, Math.min(maxSliceWork, remainingWork / 2)),
            Math.max(1L, Math.min(maxSliceNanos, remainingNanos / 2)), clock);
    }

    /** Distinct from cancellation, internal errors and a proof of missing materials. */
    public static final class Exhausted extends RuntimeException {
        private Exhausted(String message) { super(message, null, false, false); }
    }
}
