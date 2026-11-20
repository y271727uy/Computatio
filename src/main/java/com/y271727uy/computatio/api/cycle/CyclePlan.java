package com.y271727uy.computatio.api.cycle;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

/**
 * Complete answer of one cycle solve in the caller's key and recipe types. Every map keeps the solver's entries and
 * insertion order.
 *
 * @param exactCounts       whether {@code firings} are exact execution counts
 * @param firings           exact firing count of every recipe that fires
 * @param externalDemand    material the boundary must deliver, net of the component's own byproducts
 * @param requiredSeed      start-up stock the plan needs before the loop is self-sustaining
 * @param seedShortfall     the part of {@code requiredSeed} the stock cannot cover
 * @param produced          gross production of the plan, byproducts included
 * @param deliverable       what is on hand for every required key once the plan has run
 * @param runs              the plan in compact form; always available
 * @param witness           the recipe of every single firing, only while the plan is at most 100,000 firings long
 * @param startupCandidates alternative start-up seeds; proposals, never sufficiency certificates
 */
public record CyclePlan<K, R>(
    CycleStatus status,
    boolean exactCounts,
    Map<R, BigInteger> firings,
    Map<K, BigInteger> externalDemand,
    Map<K, BigInteger> requiredSeed,
    Map<K, BigInteger> seedShortfall,
    Map<K, BigInteger> produced,
    Map<K, BigInteger> deliverable,
    List<Run<R>> runs,
    List<R> witness,
    List<Diagnostic> diagnostics,
    Metrics metrics,
    List<Map<K, BigInteger>> startupCandidates
) {
    /** Fire {@code recipe} {@code count} times per lap; see {@link RepeatRun} for circuits. */
    public record Run<R>(R recipe, BigInteger count, int repeatWidth, BigInteger repetitions) implements RepeatRun {}

    /** {@code code} is the solver's diagnostic code name, for example {@code SEED_SHORTFALL}. */
    public record Diagnostic(String code, String message) {}

    /** Search statistics of the solve. */
    public record Metrics(
        int relevantKeys,
        int transitions,
        long statesVisited,
        long statesExpanded,
        int witnessLength,
        int seedLadderSteps,
        boolean stateBudgetExhausted,
        boolean firingDepthTruncated,
        boolean amountOverflowTruncated,
        long greedyCandidates,
        long lookaheadNodes,
        long heuristicMacroSteps,
        boolean heuristicBudgetExhausted
    ) {
        public boolean truncated() {
            return stateBudgetExhausted || firingDepthTruncated || amountOverflowTruncated;
        }
    }
}
