package com.y271727uy.computatio.api.cycle;

/** Outcome of one cycle solve. Only {@link #SUCCESS} and {@link #INSUFFICIENT_EXTERNAL_INPUT} are proofs. */
public enum CycleStatus {
    /** An executable plan exists and its start-up seed is covered by stock. */
    SUCCESS,
    /** Proven: the component cannot reach the targets at the given stock and boundary. */
    INSUFFICIENT_EXTERNAL_INPUT,
    /** The allowance ran out before a proof either way. */
    UNKNOWN_BUDGET,
    /** Larger than the structural limits; nothing was attempted. */
    TOO_COMPLEX,
    /** A recipe is not batch-safe inside a cycle. */
    UNSUPPORTED_PATTERN,
    /** A caller's fixed-width field cannot carry the exact answer; the solver itself never reports it. */
    UNREPRESENTABLE,
    CANCELLED,
    NOT_IMPLEMENTED;

    public boolean solved() {
        return this == SUCCESS;
    }

    public boolean proven() {
        return this == SUCCESS || this == INSUFFICIENT_EXTERNAL_INPUT;
    }
}
