package com.y271727uy.computatio.api.cycle;

/** The shared {@link CycleBudget} ran out; distinct from cancellation and from a proof of missing materials. */
public final class CycleBudgetExhaustedException extends RuntimeException {
    private CycleBudgetExhaustedException(String message, Throwable cause) {
        super(message, cause, false, false);
    }

    static CycleBudgetExhaustedException of(RuntimeException exhausted) {
        return new CycleBudgetExhaustedException(exhausted.getMessage(), exhausted);
    }
}
