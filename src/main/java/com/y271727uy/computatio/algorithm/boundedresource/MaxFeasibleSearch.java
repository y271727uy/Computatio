package com.y271727uy.computatio.algorithm.boundedresource;

import java.util.function.LongPredicate;

/** Searches the largest value accepted by a monotonic feasibility predicate. */
public final class MaxFeasibleSearch {
    private MaxFeasibleSearch() {}

    /**
     * Returns the largest value in [0, maximum] accepted by {@code feasible}.
     * The predicate must be monotonic: once true, every smaller value is true.
     */
    public static long find(long maximum, LongPredicate feasible) {
        if (maximum < 0L) throw new IllegalArgumentException("maximum must not be negative");
        if (feasible == null) throw new NullPointerException("feasible");
        if (!feasible.test(0L)) return 0L;
        long low = 0L;
        long high = maximum;
        while (low < high) {
            long mid = low + (high - low) / 2L + 1L;
            if (feasible.test(mid)) low = mid;
            else high = mid - 1L;
        }
        return low;
    }
}
