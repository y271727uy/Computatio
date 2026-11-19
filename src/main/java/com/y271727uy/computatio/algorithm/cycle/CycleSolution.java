package com.y271727uy.computatio.algorithm.cycle;

import com.y271727uy.computatio.algorithm.amount.PlannerAmount;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Complete answer of one cycle solve; port of NeoECO's {@code CycleSolveResult}. Keys are key indices, pattern keys
 * are transition indices, and every map keeps NeoECO's entries and insertion order.
 *
 * <ul>
 *   <li>{@code firings}: exact firing count of every transition that fires.</li>
 *   <li>{@code externalDemand}: material the boundary must deliver, net of the component's own byproducts.</li>
 *   <li>{@code requiredSeed}: start-up stock the witness needs before the loop is self-sustaining.</li>
 *   <li>{@code seedShortfall}: the part of {@code requiredSeed} the stock snapshot cannot cover.</li>
 *   <li>{@code produced}: gross production of the witness, byproducts included.</li>
 *   <li>{@code deliverable}: what is on hand for every required key once the witness has run (zeros included).</li>
 *   <li>{@code witness}: the transition of every single firing, only while the order has at most 100,000 firings.</li>
 *   <li>{@code plan}: the same work in compact {@code transition x count} form; always available.</li>
 * </ul>
 */
public record CycleSolution(
    CycleSolveStatus status,
    ExecutionCountKnowledge countKnowledge,
    Map<Integer, PlannerAmount> firings,
    Map<Integer, PlannerAmount> externalDemand,
    Map<Integer, PlannerAmount> requiredSeed,
    Map<Integer, PlannerAmount> seedShortfall,
    Map<Integer, PlannerAmount> produced,
    Map<Integer, PlannerAmount> deliverable,
    List<Integer> witness,
    List<CycleRun> plan,
    List<CycleSolveDiagnostic> diagnostics,
    CycleSolveMetrics metrics,
    List<Map<Integer, PlannerAmount>> startupCandidates
) {
    public CycleSolution {
        countKnowledge = countKnowledge == null ? ExecutionCountKnowledge.UNKNOWN : countKnowledge;
        RepeatLayout.plannerMultipliers(plan);
        startupCandidates = startupCandidates == null ? List.of() : startupCandidates;
        if (status == CycleSolveStatus.SUCCESS && !seedShortfall.isEmpty()) {
            throw new IllegalArgumentException("A successful cycle solve cannot report a seed shortfall");
        }
        if (!witness.isEmpty() && !totalRunCount(plan).equals(PlannerAmount.of(witness.size()))) {
            throw new IllegalArgumentException("A compact execution plan must account for every witness step");
        }
    }

    private static PlannerAmount totalRunCount(List<CycleRun> runs) {
        PlannerAmount total = PlannerAmount.ZERO;
        PlannerAmount[] repeats = RepeatLayout.plannerMultipliers(runs);
        for (int i = 0; i < runs.size(); i++) total = total.add(runs.get(i).count().multiply(repeats[i]));
        return total;
    }

    public static CycleSolution failure(CycleSolveStatus status, List<CycleSolveDiagnostic> diagnostics,
            CycleSolveMetrics metrics) {
        return new CycleSolution(status, ExecutionCountKnowledge.UNKNOWN, Map.of(), Map.of(), Map.of(), Map.of(),
            Map.of(), Map.of(), List.of(), List.of(), diagnostics, metrics, List.of());
    }

    public static CycleSolution failure(CycleSolveStatus status, CycleSolveDiagnostic.Code code, String message) {
        return failure(status, List.of(new CycleSolveDiagnostic(code, message)), CycleSolveMetrics.NONE);
    }

    public static CycleSolution cancelled() {
        return failure(CycleSolveStatus.CANCELLED, CycleSolveDiagnostic.Code.CANCELLED, "Cycle solving was cancelled");
    }

    public CycleSolution withExactStartupCandidates(List<Map<Integer, PlannerAmount>> candidates) {
        return new CycleSolution(status, countKnowledge, firings, externalDemand, requiredSeed, seedShortfall,
            produced, deliverable, witness, plan, diagnostics, metrics, candidates);
    }

    /** Pure annotation: same answer, extra explanation placed before the existing entries. */
    public CycleSolution withAdditionalDiagnostics(List<CycleSolveDiagnostic> extra) {
        if (extra.isEmpty()) return this;
        List<CycleSolveDiagnostic> merged = new ArrayList<>(extra);
        merged.addAll(diagnostics);
        return new CycleSolution(status, countKnowledge, firings, externalDemand, requiredSeed, seedShortfall,
            produced, deliverable, witness, plan, merged, metrics, startupCandidates);
    }

    public boolean hasExactExecutionCounts() {
        return countKnowledge == ExecutionCountKnowledge.EXACT;
    }

    /** Firing count of one transition; zero when it does not fire or counts are unknown. */
    public PlannerAmount firingsOf(int transition) {
        return firings.getOrDefault(transition, PlannerAmount.ZERO);
    }

    /** Exact total firings in the plan. */
    public PlannerAmount totalFirings() {
        PlannerAmount total = PlannerAmount.ZERO;
        for (PlannerAmount count : firings.values()) total = total.add(count);
        return total;
    }

    public String summary() {
        String detail = diagnostics.stream()
            .map(diagnostic -> diagnostic.code() + ": " + diagnostic.message())
            .collect(Collectors.joining("; "));
        return detail.isEmpty() ? status.name() : status.name() + " (" + detail + ")";
    }

    /**
     * The transition of every single firing in plan order, or {@code null} when that would exceed {@code maxSteps}.
     * Unlike {@link #witness()} this expands any plan up to the requested size.
     */
    public int[] expandedPlan(int maxSteps) {
        PlannerAmount total = totalRunCount(plan);
        if (total.compareTo(PlannerAmount.of(maxSteps)) > 0) return null;
        int[] steps = new int[(int) total.longValueExact()];
        // As NeoECO's expandWitness: only the last run of a circuit carries its lap count.
        long[] remaining = new long[plan.size()];
        for (int i = 0; i < remaining.length; i++) remaining[i] = plan.get(i).plannerRepetitions().longValueExact();
        int cursor = 0;
        for (int index = 0; index < plan.size();) {
            CycleRun run = plan.get(index);
            for (long c = 0; c < run.count().longValueExact(); c++) steps[cursor++] = run.transition();
            if (run.repeated() && --remaining[index] > 0) index -= run.repeatWidth() - 1;
            else index++;
        }
        return steps;
    }
}
