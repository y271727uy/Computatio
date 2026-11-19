package com.y271727uy.computatio.algorithm.cycle;

import com.y271727uy.computatio.algorithm.amount.PlannerAmount;

/**
 * Conservative retained-size estimates, not a cap on recipe count or integer precision. Port of NeoECO's
 * {@code CycleMemoryBudget}; the default is NeoECO's configuration default of 64 MiB.
 */
public final class CycleMemoryBudget {
    public static final long DEFAULT_MAX_BYTES = 64L * 1024L * 1024L;

    private final long maximum;
    private long retained;

    public CycleMemoryBudget() {
        this(DEFAULT_MAX_BYTES);
    }

    public CycleMemoryBudget(long maximum) {
        if (maximum < 1) throw new IllegalArgumentException("Memory allowance must be positive");
        this.maximum = maximum;
    }

    void retain(long bytes) {
        check(retained, bytes);
        retained += bytes;
    }

    void release(long bytes) { retained -= bytes; }

    long retainedBytes() { return retained; }

    void check(long temporary) { check(retained, temporary); }

    private void check(long existing, long additional) {
        if (additional < 0 || additional > maximum - existing) {
            throw new Exhausted("Estimated cycle data exceeds " + maximum + " bytes; result remains unknown");
        }
    }

    // Include object/array headers and eight-byte references even on compressed-oops JVMs.
    static long integerBytes(PlannerAmount value) {
        if (value.fitsLong()) return 48L;
        if (value.fitsDoubleLong()) return 80L;
        return 128L + 4L * ((value.bitLength() + 31L) / 32L);
    }

    static long markingBytes(PlannerAmount[] marking) {
        long bytes = 192L + 16L * marking.length; // Node, queue/set entries and two inventory arrays.
        for (PlannerAmount amount : marking) bytes += 32L + integerBytes(amount);
        return bytes;
    }

    public static final class Exhausted extends RuntimeException {
        Exhausted(String message) { super(message, null, false, false); }
    }
}
