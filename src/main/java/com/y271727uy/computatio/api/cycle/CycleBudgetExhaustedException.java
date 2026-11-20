package com.y271727uy.computatio.api.cycle;

/**
 * The shared {@link CycleBudget} ran out; distinct from cancellation and from a proof of missing materials.
 *
 * <p>A {@link CycleCheck} may throw it to report that the caller's own outer allowance ran out. The cycle algorithms
 * then treat it exactly like their own budget running out: an optional stage is abandoned and a result already proven
 * is kept, where the same would happen for a {@link CycleBudget}.
 */
public final class CycleBudgetExhaustedException extends RuntimeException {
    public CycleBudgetExhaustedException(String message) {
        super(message, null, false, false);
    }

    private CycleBudgetExhaustedException(String message, Throwable cause) {
        super(message, cause, false, false);
    }

    static CycleBudgetExhaustedException of(RuntimeException exhausted) {
        return new CycleBudgetExhaustedException(exhausted.getMessage(), exhausted);
    }
}
