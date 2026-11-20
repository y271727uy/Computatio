package com.y271727uy.computatio.api.cycle;

import java.math.BigInteger;

/**
 * Exact integer answer of a material balance: {@code counts[j]} is how often column {@code j} fires, or {@code null}
 * when {@link Status#INFEASIBLE} or {@link Status#UNKNOWN}.
 */
public record MaterialBalance(Status status, BigInteger[] counts) {
    public enum Status {
        /** Proven minimum. */
        OPTIMAL,
        /** A valid integer vector, not proven minimal (first-feasible mode). */
        FEASIBLE,
        /** Proven: no vector satisfies the targets. */
        INFEASIBLE,
        /** The allowance ran out first. */
        UNKNOWN
    }
}
