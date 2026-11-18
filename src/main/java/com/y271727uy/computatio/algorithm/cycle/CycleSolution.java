package com.y271727uy.computatio.algorithm.cycle;

import com.y271727uy.computatio.algorithm.amount.PlannerAmount;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Complete answer of one cycle solve, indexed by the {@link CycleNet}'s transition and key numbers.
 *
 * <ul>
 *   <li>{@code firings} — exact firing count of each transition; empty when unknown.</li>
 *   <li>{@code plan} — a concrete, replayable, never-negative firing order in compact {@link CycleRun} form.</li>
 *   <li>{@code externalDemand} — material the boundary must deliver, net of the component's own byproducts.</li>
 *   <li>{@code requiredSeed} — start-up stock the plan needs before the loop is self-sustaining.</li>
 *   <li>{@code seedShortfall} — the part of {@code requiredSeed} the stock snapshot cannot cover.</li>
 *   <li>{@code produced} — gross production of the plan, byproducts included.</li>
 *   <li>{@code deliverable} — on-hand amount after the plan for keys with a positive target; zero elsewhere.</li>
 *   <li>{@code startupCandidates} — alternative first-firing material proposals (never sufficiency proofs).</li>
 * </ul>
 *
 * <p>Per-key lists have one entry per key, or are empty when the outcome carries no material accounting.
 * {@code requiredSeed} together with {@code externalDemand} is exactly what the plan needs: replaying it from
 * {@code requiredSeed}, importing {@code externalDemand} across the boundary, keeps every stock level at or above
 * zero.
 */
public record CycleSolution(
    CycleSolveStatus status,
    ExecutionCountKnowledge countKnowledge,
    List<PlannerAmount> firings,
    List<CycleRun> plan,
    List<PlannerAmount> externalDemand,
    List<PlannerAmount> requiredSeed,
    List<PlannerAmount> seedShortfall,
    List<PlannerAmount> produced,
    List<PlannerAmount> deliverable,
    List<List<PlannerAmount>> startupCandidates,
    List<CycleSolveDiagnostic> diagnostics,
    CycleSolveMetrics metrics
) {
    public CycleSolution {
        countKnowledge = countKnowledge == null ? ExecutionCountKnowledge.UNKNOWN : countKnowledge;
        firings = List.copyOf(firings);
        plan = List.copyOf(plan);
        externalDemand = List.copyOf(externalDemand);
        requiredSeed = List.copyOf(requiredSeed);
        seedShortfall = List.copyOf(seedShortfall);
        produced = List.copyOf(produced);
        deliverable = List.copyOf(deliverable);
        startupCandidates = startupCandidates.stream().map(List::copyOf).collect(Collectors.toUnmodifiableList());
        diagnostics = List.copyOf(diagnostics);
        metrics = metrics == null ? CycleSolveMetrics.NONE : metrics;
        validateLayout(plan);
        if (status == CycleSolveStatus.SUCCESS && seedShortfall.stream().anyMatch(amount -> amount.signum() > 0)) {
            throw new IllegalArgumentException("A successful cycle solve cannot report a seed shortfall");
        }
    }

    public static CycleSolution failure(CycleSolveStatus status, List<CycleSolveDiagnostic> diagnostics,
            CycleSolveMetrics metrics) {
        return new CycleSolution(status, ExecutionCountKnowledge.UNKNOWN, List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), List.of(), List.of(), diagnostics, metrics);
    }

    public static CycleSolution failure(CycleSolveStatus status, CycleSolveDiagnostic.Code code, String message) {
        return failure(status, List.of(new CycleSolveDiagnostic(code, message)), CycleSolveMetrics.NONE);
    }

    public static CycleSolution cancelled() {
        return failure(CycleSolveStatus.CANCELLED, CycleSolveDiagnostic.Code.CANCELLED, "Cycle solving was cancelled");
    }

    /** Alternative startup candidates are proposals, never execution or sufficiency certificates. */
    public CycleSolution withStartupCandidates(List<List<PlannerAmount>> candidates) {
        return new CycleSolution(status, countKnowledge, firings, plan, externalDemand, requiredSeed, seedShortfall,
            produced, deliverable, candidates, diagnostics, metrics);
    }

    /** Pure annotation: same answer, extra explanation placed before the existing entries. */
    public CycleSolution withAdditionalDiagnostics(List<CycleSolveDiagnostic> extra) {
        if (extra.isEmpty()) return this;
        List<CycleSolveDiagnostic> merged = new ArrayList<>(extra);
        merged.addAll(diagnostics);
        return new CycleSolution(status, countKnowledge, firings, plan, externalDemand, requiredSeed, seedShortfall,
            produced, deliverable, startupCandidates, merged, metrics);
    }

    public boolean hasExactExecutionCounts() {
        return countKnowledge == ExecutionCountKnowledge.EXACT;
    }

    /**
     * True when an execution amount leaves the {@code long} range under NeoECO's rule (any runtime count, start-up
     * seed, external demand or shortfall); the result then carries one {@code EXECUTION_AMOUNT_UNREPRESENTABLE}
     * diagnostic per amount. The amounts here stay exact; a long-based caller such as NeoECO reports this result as
     * {@link CycleSolveStatus#UNREPRESENTABLE}. The solver already takes NeoECO's branches on this condition.
     */
    public boolean exceedsLongRange() {
        for (CycleSolveDiagnostic diagnostic : diagnostics) {
            if (diagnostic.code() == CycleSolveDiagnostic.Code.EXECUTION_AMOUNT_UNREPRESENTABLE) return true;
        }
        return false;
    }

    /** Firing count of one transition; zero when counts are unknown or the transition does not fire. */
    public PlannerAmount firingsOf(int transition) {
        return transition < firings.size() ? firings.get(transition) : PlannerAmount.ZERO;
    }

    /** Exact total firings in the plan. */
    public PlannerAmount totalFirings() {
        PlannerAmount total = PlannerAmount.ZERO;
        for (PlannerAmount count : firings) total = total.add(count);
        return total;
    }

    public String summary() {
        String detail = diagnostics.stream()
            .map(diagnostic -> diagnostic.code() + ": " + diagnostic.message())
            .collect(Collectors.joining("; "));
        return detail.isEmpty() ? status.name() : status.name() + " (" + detail + ")";
    }

    /**
     * Expands the compact plan into one transition index per firing, or returns {@code null} when the plan has
     * more than {@code maxSteps} firings.
     */
    public int[] expandedPlan(int maxSteps) {
        PlannerAmount total = PlannerAmount.ZERO;
        PlannerAmount limit = PlannerAmount.of(Math.max(0, maxSteps));
        for (int end = 0; end < plan.size(); end++) {
            CycleRun run = plan.get(end);
            total = total.add(run.count());
            if (run.repeated()) {
                PlannerAmount perLap = PlannerAmount.ZERO;
                for (int i = end - run.repeatWidth() + 1; i <= end; i++) perLap = perLap.add(plan.get(i).count());
                total = total.add(perLap.multiply(run.repetitions().subtract(PlannerAmount.ONE)));
            }
            if (total.compareTo(limit) > 0) return null;
        }
        int[] result = new int[(int) total.longValueExact()];
        int cursor = 0;
        long[] laps = new long[plan.size()];
        for (int i = 0; i < laps.length; i++) laps[i] = plan.get(i).repetitions().longValueExact();
        for (int index = 0; index < plan.size();) {
            CycleRun run = plan.get(index);
            for (long count = 0; count < run.count().longValueExact(); count++) result[cursor++] = run.transition();
            if (run.repeated() && --laps[index] > 0) index -= run.repeatWidth() - 1;
            else index++;
        }
        return result;
    }

    private static void validateLayout(List<CycleRun> plan) {
        int previousEnd = -1;
        for (int end = 0; end < plan.size(); end++) {
            CycleRun run = plan.get(end);
            if (!run.repeated()) continue;
            int start = end - run.repeatWidth() + 1;
            if (start < 0 || start <= previousEnd) throw new IllegalArgumentException("Overlapping recipe circuit repeats");
            previousEnd = end;
        }
    }
}
