package com.y271727uy.computatio.algorithm.cycle;

import com.y271727uy.computatio.algorithm.amount.PlannerAmount;
import it.unimi.dsi.fastutil.ints.IntHeapPriorityQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Cyclic component solver: exact integer balance, verified compact circuits, then bounded marking search.
 *
 * <h2>Model</h2>
 * The component is read as a small Petri net ({@link CycleNet}). A transition fires only when every input it
 * consumes from inside the component is actually on hand, so no marking can ever go negative and nothing is
 * produced from nothing.
 *
 * <h2>Boundary</h2>
 * Boundary keys are supplied by the rest of the caller's dependency graph, so a deficit on them is imported and
 * booked as external demand instead of blocking the firing. Every other key has no outside producer: its deficit
 * is a start-up seed, which only stock can cover.
 *
 * <h2>Honesty rules</h2>
 * {@link CycleSolveStatus#INSUFFICIENT_EXTERNAL_INPUT} is returned only when the reachable marking set was closed
 * exhaustively, or exact integer material balance is infeasible. Any early stop yields
 * {@link CycleSolveStatus#UNKNOWN_BUDGET}. Nothing here ever reports plain missing items, and no result is cached:
 * every answer belongs to the one stock snapshot it was computed from.
 *
 * <p>Cancellation is propagated as {@link InterruptedException}; {@link PlanningBudget.Exhausted} is rethrown so
 * the caller can distinguish a shared-allowance cut-off from a proof.
 */
public final class BoundedCycleSolver implements CycleSolver {
    /** Expanded per-firing witness length reported in metrics only while it remains cheap to materialize. */
    public static final long MAX_EXPANDED_WITNESS = 100_000L;

    private final long maxMemoryBytes;

    public BoundedCycleSolver() {
        this(CycleMemoryBudget.DEFAULT_MAX_BYTES);
    }

    public BoundedCycleSolver(long maxMemoryBytes) {
        if (maxMemoryBytes < 1) throw new IllegalArgumentException("Memory allowance must be positive");
        this.maxMemoryBytes = maxMemoryBytes;
    }

    /**
     * Verify a caller's fixed integer candidate with the prefix/repeat algebra, without optimizing it again.
     * Returns {@code null} when the net does not admit circuit algebra, a count is negative, or the certificate
     * would not verify exactly this candidate.
     */
    public CycleSolution verifyFirings(CycleNet net, PlannerAmount[] counts, CycleSolveLimits limits,
            Cancellation cancellation) throws InterruptedException {
        CycleMemoryBudget memory = new CycleMemoryBudget(maxMemoryBytes);
        try {
            Object prepared = prepare(net, limits, cancellation, memory);
            if (prepared instanceof CycleSolution) return (CycleSolution) prepared;
            Model model = (Model) prepared;
            if (!model.circuitSafe || counts.length != model.transitionCount()) return null;
            PlannerAmount[] vector = new PlannerAmount[model.transitionCount()];
            for (int i = 0; i < vector.length; i++) {
                vector[i] = counts[i] == null ? PlannerAmount.ZERO : counts[i];
                if (vector[i].signum() < 0) return null;
            }
            CycleSolution result = solveEquationWitness(model,
                new CycleStateEquation.Result(CycleStateEquation.Status.FEASIBLE, vector), cancellation, memory);
            // The certificate must verify this exact candidate, not a repaired or truncated vector.
            if (result != null && !result.firings().equals(Arrays.asList(vector))) return null;
            return result;
        } catch (CycleMemoryBudget.Exhausted exhausted) {
            return CycleSolution.failure(CycleSolveStatus.UNKNOWN_BUDGET,
                CycleSolveDiagnostic.Code.MEMORY_BUDGET_EXHAUSTED, exhausted.getMessage());
        }
    }

    @Override
    public CycleSolution solve(CycleNet net, CycleSolveLimits limits, Cancellation cancellation)
            throws InterruptedException {
        cancellation.checkpoint();
        try {
            return run(net, limits == null ? CycleSolveLimits.DEFAULT : limits, cancellation);
        } catch (CycleMemoryBudget.Exhausted exhausted) {
            return CycleSolution.failure(CycleSolveStatus.UNKNOWN_BUDGET,
                CycleSolveDiagnostic.Code.MEMORY_BUDGET_EXHAUSTED, exhausted.getMessage());
        }
    }

    private CycleSolution run(CycleNet net, CycleSolveLimits limits, Cancellation cancellation)
            throws InterruptedException {
        CycleMemoryBudget memory = new CycleMemoryBudget(maxMemoryBytes);
        Object prepared = prepare(net, limits, cancellation, memory);
        if (prepared instanceof CycleSolution) return (CycleSolution) prepared;
        Model model = (Model) prepared;

        List<CycleSolveDiagnostic> diagnostics = new ArrayList<>();
        if (satisfied(model.stock, model.required)) {
            CycleSolveMetrics stockMetrics = new CycleSolveMetrics(model.keyCount(), model.transitionCount(), 1, 0,
                0, 0, false, false);
            diagnostics.add(new CycleSolveDiagnostic(CycleSolveDiagnostic.Code.SATISFIED_FROM_STOCK,
                "Relevant stock already covers every required output; the structural cycle is cut by inventory"));
            diagnostics.add(metrics(stockMetrics));
            return new CycleSolution(CycleSolveStatus.SUCCESS, ExecutionCountKnowledge.EXACT,
                Arrays.asList(zeroes(model.transitionCount())), List.of(), List.of(), List.of(), List.of(), List.of(),
                Arrays.asList(exactDeliverable(model, model.stock)), List.of(), diagnostics, stockMetrics);
        }

        CycleSolution growth = growthWitness(model, growth(model));
        if (growth != null) return growth;

        boolean recipeCircuits = model.circuitSafe;
        CycleStateEquation.Result balance = recipeCircuits
            ? CycleStateEquation.solve(model.cons, model.prod, model.suppliable, model.stock, model.required,
                cancellation, memory)
            : new CycleStateEquation.Result(CycleStateEquation.Status.UNKNOWN, null);
        if (balance.status() == CycleStateEquation.Status.INFEASIBLE) {
            diagnostics.add(new CycleSolveDiagnostic(CycleSolveDiagnostic.Code.STATE_EQUATION_INFEASIBLE,
                "Exact material balance has no nonnegative integer firing vector at current stock"));
            CycleSolution materials = fullOrderDeficit(model, cancellation, memory);
            if (materials != null) return materials.withAdditionalDiagnostics(diagnostics);
            boolean noGrowth = true;
            for (int k = 0; k < model.keyCount(); k++) {
                if (model.required[k].compareTo(model.stock[k]) <= 0) continue;
                for (int t = 0; t < model.transitionCount(); t++)
                    if (model.prod[t][k] > model.cons[t][k]) noGrowth = false;
            }
            if (noGrowth) return witnessResult(model, addMissingTargets(model), List.of(), diagnostics,
                CycleSolveMetrics.NONE).withStartupCandidates(startupCandidates(model, cancellation, memory));
            return CycleSolution.failure(CycleSolveStatus.INSUFFICIENT_EXTERNAL_INPUT, diagnostics,
                CycleSolveMetrics.NONE).withStartupCandidates(startupCandidates(model, cancellation, memory));
        }
        CycleSolution equation = balance.counts() == null ? null
            : solveEquationWitness(model, balance, cancellation, memory);
        if (equation != null && equation.status() == CycleSolveStatus.SUCCESS) return equation;
        if (!recipeCircuits) {
            StringBuilder contracts = new StringBuilder();
            for (int t = 0; t < model.transitionCount(); t++) {
                if (model.net.circuitSafe(t)) continue;
                if (contracts.length() > 0) contracts.append("; ");
                contracts.append("transition=").append(model.net.transitionName(t));
            }
            diagnostics.add(new CycleSolveDiagnostic(CycleSolveDiagnostic.Code.STATIC_CYCLE_CONTRACT_UNAVAILABLE,
                "Exact circuit algebra is unavailable for: " + contracts + "; using bounded marking search"));
        } else if (balance.status() == CycleStateEquation.Status.UNKNOWN
                || balance.status() == CycleStateEquation.Status.FEASIBLE) {
            diagnostics.add(new CycleSolveDiagnostic(CycleSolveDiagnostic.Code.STATE_EQUATION_BUDGET,
                "No static firing vector is available; marking search verifies reachability under the same resource budget"));
        }

        int budget = limits.maxStates();
        Search first = search(model, model.stock, budget, limits.maxFirings(), cancellation, memory);
        long visited = first.statesVisited;
        long expanded = first.statesExpanded;

        if (first.kind == Search.Kind.REACHED) {
            diagnostics.add(new CycleSolveDiagnostic(CycleSolveDiagnostic.Code.WITNESS_FOUND,
                "Found a verified batch firing order of " + first.witness.size()
                    + " macro-step(s) within the stock snapshot"));
            return witnessResult(model, model.stock, first.witness, diagnostics,
                new CycleSolveMetrics(model.keyCount(), model.transitionCount(), visited, expanded,
                    expandedWitnessLength(first.witness), 0, false, false, false,
                    first.greedyCandidates, first.lookaheadNodes, first.heuristicMacroSteps,
                    first.heuristicBudgetExhausted));
        }
        if (first.kind != Search.Kind.EXHAUSTED) {
            return budgetResult(model, first, visited, expanded, 0).withAdditionalDiagnostics(diagnostics);
        }

        diagnostics.add(new CycleSolveDiagnostic(CycleSolveDiagnostic.Code.PROVEN_INFEASIBLE_AT_CURRENT_STOCK,
            "Explored the complete reachable marking set (" + visited
                + " states) without reaching the required outputs"));

        // A seed deficit in one constructed witness is not a reachability proof. Only return that seed
        // proposal after the original-stock search has actually closed; budget cuts above stay unknown.
        if (equation != null && equation.status() == CycleSolveStatus.INSUFFICIENT_EXTERNAL_INPUT) {
            return equation.withAdditionalDiagnostics(diagnostics)
                .withStartupCandidates(startupCandidates(model, cancellation, memory));
        }

        CycleSolution materials = fullOrderDeficit(model, cancellation, memory);
        if (materials != null) return materials.withAdditionalDiagnostics(diagnostics);

        PlannerAmount[] base = first.unblockDeficit;
        if (base == null || isZero(base)) {
            CycleSolveMetrics deadMetrics = new CycleSolveMetrics(model.keyCount(), model.transitionCount(),
                visited, expanded, 0, 0, false, false);
            diagnostics.add(new CycleSolveDiagnostic(CycleSolveDiagnostic.Code.NO_PRODUCTIVE_FIRING,
                "Every component transition stays fireable yet no reachable marking increases the required outputs"));
            diagnostics.add(metrics(deadMetrics));
            return CycleSolution.failure(CycleSolveStatus.INSUFFICIENT_EXTERNAL_INPUT, diagnostics, deadMetrics);
        }

        diagnostics.add(new CycleSolveDiagnostic(CycleSolveDiagnostic.Code.SEED_ESTIMATE_LOWER_BOUND,
            "No static full-order certificate is available; the reported seed is the smallest amount that unblocks"
                + " the deadlock, not a proven sufficient amount"));
        PlannerAmount[] exactShortfall = zeroes(model.keyCount());
        PlannerAmount[] exactSeed = zeroes(model.keyCount());
        for (int i = 0; i < model.keyCount(); i++) {
            if (base[i].signum() > 0) {
                exactSeed[i] = model.stock[i].max(PlannerAmount.ZERO).add(base[i]);
                exactShortfall[i] = base[i];
            }
        }
        CycleSolveMetrics ladderMetrics = new CycleSolveMetrics(model.keyCount(), model.transitionCount(), visited,
            expanded, 0, 0, false, false);
        diagnostics.add(new CycleSolveDiagnostic(CycleSolveDiagnostic.Code.SEED_SHORTFALL,
            "Short of " + describe(model, base) + " to start the loop"));
        diagnostics.add(metrics(ladderMetrics));
        return new CycleSolution(CycleSolveStatus.INSUFFICIENT_EXTERNAL_INPUT, ExecutionCountKnowledge.UNKNOWN,
            List.of(), List.of(), List.of(), Arrays.asList(exactSeed), Arrays.asList(exactShortfall), List.of(),
            Arrays.asList(exactDeliverable(model, model.stock)), List.of(), diagnostics, ladderMetrics)
            .withStartupCandidates(startupCandidates(model, cancellation, memory));
    }

    // ---------------------------------------------------------------------------------------------------
    // Structural preparation
    // ---------------------------------------------------------------------------------------------------

    /** Returns a {@link Model}, or a {@link CycleSolution} when the component is out of scope. */
    private static Object prepare(CycleNet net, CycleSolveLimits limits, Cancellation cancellation,
            CycleMemoryBudget memory) throws InterruptedException {
        int t = net.transitionCount();
        int n = net.keyCount();
        if (t == 0) {
            return CycleSolution.failure(CycleSolveStatus.UNSUPPORTED_PATTERN,
                CycleSolveDiagnostic.Code.NO_TRANSITIONS, "Cyclic component carries no transition");
        }
        if (limits.maxPatterns() > 0 && t > limits.maxPatterns()) {
            return CycleSolution.failure(CycleSolveStatus.TOO_COMPLEX,
                CycleSolveDiagnostic.Code.PATTERN_LIMIT_EXCEEDED,
                "Cyclic component has " + t + " transitions, limit is " + limits.maxPatterns());
        }
        for (int p = 0; p < t; p++) {
            cancellation.checkpoint();
            boolean output = false;
            for (int i = 0; i < n; i++) output |= net.produced(p, i) > 0;
            if (!output) {
                return CycleSolution.failure(CycleSolveStatus.UNSUPPORTED_PATTERN,
                    CycleSolveDiagnostic.Code.UNSUPPORTED_PATTERN,
                    "Transition " + net.transitionName(p) + " is not batch-safe inside a cycle: NO_OUTPUTS");
            }
        }
        if (limits.maxKeys() > 0 && n > limits.maxKeys()) {
            return CycleSolution.failure(CycleSolveStatus.TOO_COMPLEX,
                CycleSolveDiagnostic.Code.KEY_LIMIT_EXCEEDED,
                "Cyclic component touches " + n + " keys, limit is " + limits.maxKeys());
        }

        memory.retain(256L + 256L * n + 256L * t + 24L * t * n);
        long[][] cons = net.consumedMatrix();
        long[][] prod = net.producedMatrix();
        boolean[] suppliable = net.boundaryArray().clone();
        boolean[] member = net.memberArray();
        for (int i = 0; i < n; i++) if (member[i]) suppliable[i] = false;
        PlannerAmount[] stock = net.stockArray();
        PlannerAmount[] required = net.requiredArray();

        boolean[] producesRequired = new boolean[t];
        for (int p = 0; p < t; p++) {
            for (int i = 0; i < n; i++) {
                if (required[i].signum() > 0 && prod[p][i] > cons[p][i]) {
                    producesRequired[p] = true;
                    break;
                }
            }
        }
        TransitionMetadata[] metadata = new TransitionMetadata[t];
        for (int p = 0; p < t; p++) {
            cancellation.checkpoint();
            int[] internal = new int[n];
            int[] changed = new int[n];
            int internalCount = 0;
            int changedCount = 0;
            List<OutputTarget> outputs = new ArrayList<>();
            List<UnlockTarget> unlocks = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if (cons[p][i] > 0 && !suppliable[i]) internal[internalCount++] = i;
                if (cons[p][i] > 0 || prod[p][i] > 0) changed[changedCount++] = i;
                if (prod[p][i] <= cons[p][i]) continue;
                PlannerAmount delta = PlannerAmount.of(prod[p][i] - cons[p][i]);
                if (required[i].signum() > 0) outputs.add(new OutputTarget(i, delta));
                if (suppliable[i]) continue;
                LongOpenHashSet thresholds = new LongOpenHashSet();
                for (int other = 0; other < t; other++) {
                    long needed = cons[other][i];
                    if (needed > 0 && thresholds.add(needed)) {
                        memory.retain(128L);
                        unlocks.add(new UnlockTarget(i, PlannerAmount.of(needed), delta));
                    }
                }
            }
            metadata[p] = new TransitionMetadata(Arrays.copyOf(internal, internalCount),
                Arrays.copyOf(changed, changedCount), outputs.toArray(new OutputTarget[0]),
                unlocks.toArray(new UnlockTarget[0]));
        }
        // Output-only places cannot enable a transition. Their exact surplus is irrelevant to
        // reachability, but must remain in the actual marking for witness/material accounting.
        boolean[] consumedKeys = new boolean[n];
        for (int p = 0; p < t; p++) {
            for (int i = 0; i < n; i++) consumedKeys[i] |= cons[p][i] > 0L;
        }
        boolean circuitSafe = true;
        for (int p = 0; p < t; p++) circuitSafe &= net.circuitSafe(p);
        return new Model(net, cons, prod, suppliable, producesRequired, stock, required, metadata, consumedKeys,
            circuitSafe);
    }

    // ---------------------------------------------------------------------------------------------------
    // Single-transition net growth
    // ---------------------------------------------------------------------------------------------------

    private record Growth(PlannerAmount firings, String diagnostic) {}

    /**
     * Closed-form firing count for one growth-safe transition that consumes and returns a single feedback key
     * with positive net growth. Returns {@code null} for every other shape; the result is still replayed.
     */
    private static Growth growth(Model model) {
        if (model.transitionCount() != 1 || !model.net.growthSafe(0)) return null;
        int feedback = -1;
        for (int k = 0; k < model.keyCount(); k++) {
            if (model.cons[0][k] > 0 && model.prod[0][k] > 0) {
                if (feedback >= 0) return null;
                feedback = k;
            }
        }
        if (feedback < 0) return null;
        int members = 0;
        for (int k = 0; k < model.keyCount(); k++) if (model.net.member(k)) members++;
        if (members > 0 && (members != 1 || !model.net.member(feedback))) return null;
        PlannerAmount consume = PlannerAmount.of(model.cons[0][feedback]);
        PlannerAmount produce = PlannerAmount.of(model.prod[0][feedback]);
        PlannerAmount growth = produce.subtract(consume);
        if (growth.signum() <= 0) return null;
        PlannerAmount firings = PlannerAmount.ZERO;
        for (int k = 0; k < model.keyCount(); k++) {
            if (model.required[k].signum() <= 0) continue;
            PlannerAmount outstanding = model.required[k].subtract(model.stock[k].max(PlannerAmount.ZERO));
            if (outstanding.signum() <= 0) continue;
            PlannerAmount perFiring = PlannerAmount.of(model.prod[0][k]).subtract(PlannerAmount.of(model.cons[0][k]));
            if (perFiring.signum() <= 0) return null;
            firings = firings.max(outstanding.ceilDiv(perFiring));
        }
        if (firings.signum() <= 0) return null;
        PlannerAmount shortfall = consume.subtract(model.stock[feedback].max(PlannerAmount.ZERO)).max(PlannerAmount.ZERO);
        String name = model.net.keyName(feedback);
        return new Growth(firings, consume + " " + name + " -> " + produce + " " + name + " (delta +" + growth
            + "), " + firings + " firing(s), seed " + consume
            + (shortfall.signum() > 0 ? ", seed shortfall " + shortfall : " covered by stock"));
    }

    private CycleSolution growthWitness(Model model, Growth growth) {
        if (growth == null || model.transitionCount() != 1 || !model.circuitSafe
                || growth.firings().signum() <= 0) return null;
        List<BatchFiring> witness = List.of(new BatchFiring(0, growth.firings()));
        Simulation bare = simulate(model, zeroes(model.keyCount()), witness);
        PlannerAmount[] start = model.stock.clone();
        for (int key = 0; key < model.keyCount(); key++) start[key] = start[key].max(bare.lazySeed[key]);
        // A target can also name finite fuel; growth arithmetic alone must not certify its final balance.
        if (!satisfied(simulate(model, start, witness).marking, model.required)) return null;
        List<CycleSolveDiagnostic> diagnostics = new ArrayList<>();
        diagnostics.add(new CycleSolveDiagnostic(CycleSolveDiagnostic.Code.SINGLE_PATTERN_NET_GROWTH,
            "Computed exact firing count algebraically and replayed one compact run: " + growth.diagnostic()));
        return witnessResult(model, start, witness, diagnostics,
            new CycleSolveMetrics(model.keyCount(), 1, 0, 0, expandedWitnessLength(witness), 0, false, false));
    }

    // ---------------------------------------------------------------------------------------------------
    // State-equation witness construction
    // ---------------------------------------------------------------------------------------------------

    private static PlannerAmount[] addMissingTargets(Model model) {
        PlannerAmount[] start = model.stock.clone();
        for (int k = 0; k < start.length; k++) start[k] = start[k].max(model.required[k]);
        return start;
    }

    private CycleSolution solveEquationWitness(Model model, CycleStateEquation.Result balance,
            Cancellation cancellation, CycleMemoryBudget memory) throws InterruptedException {
        return solveEquationWitness(model, balance, cancellation, memory, false);
    }

    private CycleSolution solveEquationWitness(Model model, CycleStateEquation.Result balance,
            Cancellation cancellation, CycleMemoryBudget memory, boolean materialReport) throws InterruptedException {
        List<BatchFiring> witness = equationWitness(model, balance.counts(), cancellation, memory, materialReport);
        if (witness == null) return null;
        Simulation bare = simulate(model, zeroes(model.keyCount()), witness);
        PlannerAmount[] start = model.stock.clone();
        for (int key = 0; key < start.length; key++) start[key] = start[key].max(bare.lazySeed[key]);
        List<CycleSolveDiagnostic> diagnostics = new ArrayList<>();
        diagnostics.add(new CycleSolveDiagnostic(CycleSolveDiagnostic.Code.STATE_EQUATION_WITNESS,
            "Constructed and replayed " + witness.size() + " compact steps from "
                + (balance.status() == CycleStateEquation.Status.OPTIMAL ? "a minimum" : "a feasible")
                + " integer firing vector; material balance alone does not certify startup"));
        return witnessResult(model, start, witness, diagnostics,
            new CycleSolveMetrics(model.keyCount(), model.transitionCount(), witness.size() + 1L,
                witness.size(), expandedWitnessLength(witness), 0, false, false));
    }

    /**
     * Only called after current-stock infeasibility is proven. Imported ingredients are proposals; the original
     * model replays the whole order and the caller checks their actual supply.
     */
    private CycleSolution fullOrderDeficit(Model model, Cancellation cancellation, CycleMemoryBudget memory)
            throws InterruptedException {
        if (!model.circuitSafe) return null;
        boolean[] importable = model.suppliable.clone();
        for (int k = 0; k < importable.length; k++) {
            // Never satisfy the requested output by pretending it was supplied as an ingredient.
            if (model.required[k].isZero()) importable[k] = true;
        }
        CycleStateEquation.Result balance = CycleStateEquation.solve(model.cons, model.prod, importable,
            model.stock, model.required, cancellation, memory);
        if (balance.counts() == null) return null;
        CycleSolution result = solveEquationWitness(model, balance, cancellation, memory, true);
        if (result == null || result.status() != CycleSolveStatus.INSUFFICIENT_EXTERNAL_INPUT
                || result.seedShortfall().stream().noneMatch(amount -> amount.signum() > 0)) return null;
        return result.withStartupCandidates(startupCandidates(model, cancellation, memory));
    }

    /** One shared prefix-deficit/repetition construction for growth, rings and split/merge circuits. */
    private static List<BatchFiring> equationWitness(Model model, PlannerAmount[] counts,
            Cancellation cancellation, CycleMemoryBudget memory, boolean materialReport) throws InterruptedException {
        PlannerAmount[] remaining = counts.clone();
        PlannerAmount[] marking = model.stock.clone();
        List<BatchFiring> witness = new ArrayList<>();
        while (true) {
            cancellation.checkpoint();
            if (isZero(remaining)) return witness;
            int lapStart = witness.size();
            for (int t = 0; t < model.transitionCount(); t++) {
                cancellation.checkpoint();
                PlannerAmount safe = maximumSafeBatch(model, marking, t).min(remaining[t]);
                if (safe.signum() <= 0) continue;
                marking = fireBatch(model, marking, t, safe);
                remaining[t] = remaining[t].subtract(safe);
                memory.retain(64L + CycleMemoryBudget.integerBytes(safe.toBigInteger()));
                witness.add(new BatchFiring(t, safe));
            }
            if (lapStart < witness.size()) {
                CircuitSummary lap = summarizePlain(model, witness, lapStart, witness.size());
                PlannerAmount extra = null;
                for (int t = 0; t < remaining.length; t++) {
                    if (lap.counts[t].signum() <= 0) continue;
                    PlannerAmount available = remaining[t].divide(lap.counts[t]);
                    extra = extra == null ? available : extra.min(available);
                }
                extra = safeRepetitions(model, marking, lap, extra == null ? PlannerAmount.ZERO : extra);
                if (extra.signum() > 0) {
                    repeatTail(witness, lapStart, extra);
                    marking = applyCircuit(model, marking, lap.repeated(extra));
                    for (int t = 0; t < remaining.length; t++)
                        remaining[t] = remaining[t].subtract(lap.counts[t].multiply(extra));
                } else {
                    marking = accelerateRecipeCircuit(model, marking, witness, cancellation, remaining);
                }
                continue;
            }
            // A proposal only. Original-stock search must prove deadlock before adopting missing seed.
            int best = -1;
            PlannerAmount score = null;
            for (int t = 0; t < remaining.length; t++) {
                if (remaining[t].signum() <= 0) continue;
                PlannerAmount missing = PlannerAmount.ZERO;
                for (int k : model.metadata[t].consumedInternalKeys) {
                    PlannerAmount input = PlannerAmount.of(model.cons[t][k]);
                    missing = missing.add(normalizedDeficit(input.subtract(marking[k]).max(PlannerAmount.ZERO), input));
                }
                if (best < 0 || missing.compareTo(score) < 0) { best = t; score = missing; }
            }
            if (best < 0) return null;
            for (int k : model.metadata[best].consumedInternalKeys) {
                PlannerAmount needed = PlannerAmount.of(model.cons[best][k]);
                if (materialReport && model.cons[best][k] > model.prod[best][k]) {
                    needed = needed.add(remaining[best].subtract(PlannerAmount.ONE)
                        .multiply(model.cons[best][k] - model.prod[best][k]));
                }
                marking[k] = marking[k].max(needed);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------
    // Bounded batch marking search
    // ---------------------------------------------------------------------------------------------------

    /**
     * Best-first over markings, with each edge representing a safe batch of one transition.
     *
     * <p>A successor carries a positive batch count, bounded by the current marking for internal inputs; boundary
     * inputs remain lazily importable. One-firing, target-boundary, dependency-unblocking and maximal-safe batches
     * are all retained as candidate edges. A returned path is still replayed exactly before it is accepted, so
     * batching improves search cost without weakening the non-negative-material invariant. The depth limit is a
     * limit on search macro steps, while the exact execution counts remain in the result.
     */
    private Search search(Model model, PlannerAmount[] start, int stateBudget, int maxFirings, Cancellation cancellation,
            CycleMemoryBudget memory) throws InterruptedException {
        Search outcome = new Search();
        int n = model.keyCount();
        int transitionCount = model.transitionCount();
        NodeList nodes = new NodeList();
        Set<Marking> seen = new ObjectOpenHashSet<>();
        IntHeapPriorityQueue queue = new IntHeapPriorityQueue(nodes::compare);

        PlannerAmount[] root = Arrays.copyOf(start, n);
        memory.retain(CycleMemoryBudget.markingBytes(root));
        nodes.add(new Node(root, -1, null, 0, deficitScore(model, root)));
        seen.add(new Marking(model, root));
        if (satisfied(root, model.required)) {
            outcome.kind = Search.Kind.REACHED;
            outcome.witness = List.of();
            outcome.statesVisited = 1;
            return outcome;
        }

        queue.enqueue(0);

        while (!queue.isEmpty()) {
            cancellation.checkpoint();
            int index = queue.dequeueInt();
            Node node = nodes.get(index);
            if (maxFirings > 0 && node.depth >= maxFirings) {
                outcome.firingDepthTruncated = true;
                continue;
            }
            outcome.statesExpanded++;
            // Accelerate the selected path without discarding other reachable markings.
            memory.check(64L * (node.depth + 1L));
            // Acceleration is a no-op without recipe circuits, so the O(depth) path rebuild is skipped there.
            List<BatchFiring> path = model.circuitSafe ? witnessOf(nodes, index) : null;
            PlannerAmount[] accelerated = path == null ? node.marking
                : accelerateRecipeCircuit(model, node.marking, path, cancellation);
            if (accelerated != node.marking && !Arrays.equals(accelerated, node.marking)) {
                if (satisfied(accelerated, model.required)) {
                    outcome.kind = Search.Kind.REACHED;
                    outcome.witness = path;
                    outcome.statesVisited = seen.size();
                    return outcome;
                }
                Marking acceleratedKey = new Marking(model, accelerated);
                if (!seen.contains(acceleratedKey)) {
                    if (stateBudget > 0 && seen.size() >= stateBudget) {
                        outcome.stateBudgetExhausted = true;
                        break;
                    }
                    memory.retain(CycleMemoryBudget.markingBytes(accelerated) + 64L * path.size());
                    Node jump = new Node(accelerated, -1, null, node.depth + 1, deficitScore(model, accelerated));
                    jump.compactWitness = path;
                    seen.add(acceleratedKey);
                    nodes.add(jump);
                    queue.enqueue(nodes.size() - 1);
                }
            }
            for (int t = 0; t < transitionCount; t++) {
                cancellation.checkpoint();
                long[] batches = candidateBatchCounts(model, node.marking, t);
                if (batches.length == 0) {
                    outcome.considerUnblock(model, node.marking, t);
                    continue;
                }
                for (long batch : batches) {
                    cancellation.checkpoint();
                    PlannerAmount[] next = fireBatch(model, node.marking, t, PlannerAmount.of(batch));
                    Marking key = new Marking(model, next);
                    if (seen.contains(key)) continue;
                    if (stateBudget > 0 && seen.size() >= stateBudget) {
                        outcome.stateBudgetExhausted = true;
                        break;
                    }
                    memory.retain(CycleMemoryBudget.markingBytes(next));
                    seen.add(key);
                    int child = nodes.size();
                    nodes.add(new Node(next, index, new BatchFiring(t, PlannerAmount.of(batch)), node.depth + 1,
                        deficitScore(model, next)));
                    if (satisfied(next, model.required)) {
                        outcome.kind = Search.Kind.REACHED;
                        outcome.witness = witnessOf(nodes, child);
                        outcome.statesVisited = seen.size();
                        return outcome;
                    }
                    queue.enqueue(child);
                }
                if (outcome.stateBudgetExhausted) break;
            }
            if (outcome.stateBudgetExhausted) break;
        }

        outcome.statesVisited = seen.size();
        if (outcome.stateBudgetExhausted) outcome.kind = Search.Kind.STATE_BUDGET;
        else if (outcome.firingDepthTruncated) outcome.kind = Search.Kind.DEPTH_TRUNCATED;
        else outcome.kind = Search.Kind.EXHAUSTED;
        return outcome;
    }

    /**
     * Returns deterministic batch sizes for one transition at one marking.
     *
     * <p>The maximal safe batch is the important fast path. The smaller candidates preserve useful alternate
     * interleavings when another transition needs an intermediate before the maximal batch would consume it all.
     */
    private static long[] candidateBatchCounts(Model model, PlannerAmount[] marking, int transition) {
        PlannerAmount maximum = maximumSafeBatch(model, marking, transition);
        if (maximum.signum() <= 0) return EMPTY_BATCHES;

        BatchCandidates candidates = new BatchCandidates();
        addBatchCandidate(candidates, PlannerAmount.ONE, maximum);

        TransitionMetadata metadata = model.metadata[transition];
        for (OutputTarget output : metadata.requiredOutputs) {
            int i = output.key;
            if (model.required[i].compareTo(marking[i]) <= 0) continue;
            addBatchCandidate(candidates, model.required[i].subtract(marking[i]).ceilDiv(output.delta), maximum);
        }

        // Add counts that make a currently disabled internal input of another transition available. This retains
        // interleavings such as "produce enough catalyst, then switch transition" without enumerating every count.
        for (UnlockTarget target : metadata.unlockTargets) {
            if (marking[target.key].compareTo(target.threshold) >= 0) continue;
            addBatchCandidate(candidates, target.threshold.subtract(marking[target.key]).ceilDiv(target.delta), maximum);
        }

        addBatchCandidate(candidates, maximum, maximum);
        return candidates.sorted();
    }

    private static PlannerAmount maximumSafeBatch(Model model, PlannerAmount[] marking, int transition) {
        PlannerAmount maximum = null;
        for (int i : model.metadata[transition].consumedInternalKeys) {
            long consumed = model.cons[transition][i];
            PlannerAmount available = marking[i].divide(PlannerAmount.of(consumed));
            maximum = maximum == null ? available : maximum.min(available);
        }
        // A transition with no internal input is already structurally unconstrained. Permit a target-directed
        // batch, but keep the candidate generator finite by never inventing an unbounded maximal successor.
        return maximum == null ? PlannerAmount.of(Long.MAX_VALUE) : maximum;
    }

    private static void addBatchCandidate(BatchCandidates candidates, PlannerAmount candidate, PlannerAmount maximum) {
        if (candidate == null || candidate.signum() <= 0 || !candidate.fitsLong()
                || candidate.compareTo(maximum) > 0) return;
        candidates.add(candidate.longValueExact());
    }

    private static PlannerAmount deficitScore(Model model, PlannerAmount[] marking) {
        PlannerAmount result = PlannerAmount.ZERO;
        for (int i = 0; i < model.keyCount(); i++) {
            if (model.required[i].compareTo(marking[i]) > 0) {
                result = result.add(normalizedDeficit(model.required[i].subtract(marking[i]), model.required[i]));
            }
        }
        return result;
    }

    /** Dimensionless fixed-point ratios; rounding only affects queue order, never proofs. */
    private static PlannerAmount normalizedDeficit(PlannerAmount missing, PlannerAmount required) {
        return missing.multiply(1_000_000L).ceilDiv(required.max(PlannerAmount.ONE));
    }

    private static List<BatchFiring> witnessOf(List<Node> nodes, int leaf) {
        ArrayDeque<BatchFiring> reversed = new ArrayDeque<>();
        int cursor = leaf;
        while (cursor > 0) {
            Node node = nodes.get(cursor);
            if (node.compactWitness != null) {
                List<BatchFiring> result = new ArrayList<>(node.compactWitness);
                result.addAll(reversed);
                return result;
            }
            reversed.addFirst(node.firing);
            cursor = node.parent;
        }
        return new ArrayList<>(reversed);
    }

    private static PlannerAmount[] fireBatch(Model model, PlannerAmount[] marking, int transition, PlannerAmount count) {
        PlannerAmount[] next = marking.clone();
        long[] cons = model.cons[transition];
        long[] prod = model.prod[transition];
        for (int i : model.metadata[transition].changedKeys) {
            if (model.suppliable[i]) {
                next[i] = advanceWithBoundarySupply(marking[i], cons[i], prod[i], count);
            } else {
                PlannerAmount consumed = PlannerAmount.of(cons[i]).multiply(count);
                PlannerAmount produced = PlannerAmount.of(prod[i]).multiply(count);
                next[i] = marking[i].subtract(consumed).add(produced);
            }
        }
        return next;
    }

    /** Final marking after a repeated transition whose boundary inputs may be imported on demand. */
    private static PlannerAmount advanceWithBoundarySupply(PlannerAmount marking, long consumed, long produced,
            PlannerAmount count) {
        if (count.signum() <= 0) return marking;
        PlannerAmount c = PlannerAmount.of(consumed);
        PlannerAmount p = PlannerAmount.of(produced);
        if (consumed <= 0L) return marking.add(p.multiply(count));
        if (produced >= consumed) {
            PlannerAmount first = marking.subtract(c).max(PlannerAmount.ZERO).add(p);
            return first.add(p.subtract(c).multiply(count.subtract(PlannerAmount.ONE)));
        }
        return marking.subtract(c.multiply(count)).add(p.multiply(count)).max(p);
    }

    private static boolean satisfied(PlannerAmount[] marking, PlannerAmount[] required) {
        for (int i = 0; i < required.length; i++) {
            if (required[i].signum() > 0 && marking[i].compareTo(required[i]) < 0) return false;
        }
        return true;
    }

    // ---------------------------------------------------------------------------------------------------
    // Result assembly
    // ---------------------------------------------------------------------------------------------------

    private CycleSolution budgetResult(Model model, Search search, long visited, long expanded, int ladderSteps) {
        List<CycleSolveDiagnostic> diagnostics = new ArrayList<>();
        if (search.stateBudgetExhausted) {
            diagnostics.add(new CycleSolveDiagnostic(CycleSolveDiagnostic.Code.STATE_BUDGET_EXHAUSTED,
                "Reached the marking budget after " + visited + " states; nothing is proven"));
        }
        if (search.firingDepthTruncated) {
            diagnostics.add(new CycleSolveDiagnostic(CycleSolveDiagnostic.Code.FIRING_DEPTH_TRUNCATED,
                "Reached the firing-depth budget; nothing is proven"));
        }
        CycleSolveMetrics metrics = new CycleSolveMetrics(model.keyCount(), model.transitionCount(), visited,
            expanded, 0, ladderSteps, search.stateBudgetExhausted, search.firingDepthTruncated, false,
            search.greedyCandidates, search.lookaheadNodes, search.heuristicMacroSteps,
            search.heuristicBudgetExhausted);
        diagnostics.add(metrics(metrics));
        return CycleSolution.failure(CycleSolveStatus.UNKNOWN_BUDGET, diagnostics, metrics);
    }

    private CycleSolution witnessResult(Model model, PlannerAmount[] start, List<BatchFiring> witness,
            List<CycleSolveDiagnostic> diagnostics, CycleSolveMetrics metrics) {
        Simulation actual = simulate(model, start, witness);
        if (!isZero(actual.lazySeed)) {
            return CycleSolution.failure(CycleSolveStatus.UNKNOWN_BUDGET,
                CycleSolveDiagnostic.Code.STATE_BUDGET_EXHAUSTED,
                "Internal inconsistency: witness replay needed unbooked seed " + describe(model, actual.lazySeed));
        }
        if (!satisfied(actual.marking, model.required)) {
            return CycleSolution.failure(CycleSolveStatus.UNKNOWN_BUDGET,
                CycleSolveDiagnostic.Code.STATE_BUDGET_EXHAUSTED,
                "Internal inconsistency: witness replay did not reach the required outputs");
        }

        Simulation bare = simulate(model, zeroes(model.keyCount()), witness);
        PlannerAmount[] exactRequiredSeed = bare.lazySeed.clone();
        PlannerAmount[] exactExternalDemand = bare.lazyImport;
        CircuitSummary summary = summarize(model, witness);
        // Sequential prefix replay needs only one returned catalyst, but a planned batch may use more.
        // Reserve those already available copies explicitly so the reported and executable concurrency agree.
        for (BatchFiring firing : witness) {
            int transition = firing.transition();
            for (int key : model.metadata[transition].consumedInternalKeys) {
                long input = model.cons[transition][key];
                if (!summary.delta[key].isZero() || model.prod[transition][key] < input) continue;
                PlannerAmount concurrent = firing.exactCount().multiply(input).min(model.stock[key]);
                if (concurrent.signum() > 0) exactRequiredSeed[key] = exactRequiredSeed[key].max(concurrent);
            }
        }

        // A speculative seed can survive the witness and satisfy the target without ever being consumed
        // (including an empty witness). Replay against real stock as well: lazy consumption seed alone would
        // omit that retained material and incorrectly report SUCCESS after an exhausted search.
        Simulation stocked = Arrays.equals(start, model.stock) ? actual : simulate(model, model.stock, witness);
        PlannerAmount[] accountedStart = model.stock.clone();
        for (int i = 0; i < model.keyCount(); i++) {
            PlannerAmount retainedShortfall = model.required[i].subtract(stocked.marking[i]);
            if (retainedShortfall.signum() > 0) {
                PlannerAmount needed = model.stock[i].add(stocked.lazySeed[i]).add(retainedShortfall);
                exactRequiredSeed[i] = exactRequiredSeed[i].max(needed);
            }
            accountedStart[i] = accountedStart[i].max(exactRequiredSeed[i]);
        }
        // Credit only stock and the seed actually reported, never surplus from a doubled probe.
        actual = Arrays.equals(accountedStart, model.stock) ? stocked : simulate(model, accountedStart, witness);
        if (!isZero(actual.lazySeed) || !satisfied(actual.marking, model.required)) {
            return CycleSolution.failure(CycleSolveStatus.UNKNOWN_BUDGET,
                CycleSolveDiagnostic.Code.STATE_BUDGET_EXHAUSTED,
                "Internal inconsistency: accounted seed did not reproduce the verified target");
        }

        PlannerAmount[] exactShortfall = zeroes(model.keyCount());
        for (int i = 0; i < model.keyCount(); i++) {
            PlannerAmount missing = exactRequiredSeed[i].subtract(model.stock[i].max(PlannerAmount.ZERO));
            if (missing.signum() > 0) exactShortfall[i] = missing;
        }

        List<CycleRun> plan = new ArrayList<>(witness.size());
        for (BatchFiring firing : witness) {
            plan.add(new CycleRun(firing.transition(), firing.exactCount(), firing.repeatWidth(), firing.repetitions()));
        }

        List<CycleSolveDiagnostic> explanation = new ArrayList<>(diagnostics);
        boolean shortfall = !isZero(exactShortfall);
        if (shortfall) {
            explanation.add(new CycleSolveDiagnostic(CycleSolveDiagnostic.Code.FULL_ORDER_MATERIAL_DEFICIT,
                "Replayed the complete requested order with the reported additional ingredients; "
                    + "deficits are sufficient for this recipe route, not merely enough to start one firing"));
        }
        explanation.add(new CycleSolveDiagnostic(
            shortfall ? CycleSolveDiagnostic.Code.SEED_SHORTFALL : CycleSolveDiagnostic.Code.SEED_COVERED_BY_STOCK,
            shortfall
                ? "Start-up seed " + describe(model, exactRequiredSeed) + " exceeds stock by "
                    + describe(model, exactShortfall)
                : "Start-up seed " + describe(model, exactRequiredSeed) + " is covered by relevant stock"));
        explanation.add(metrics(metrics));

        return new CycleSolution(
            shortfall ? CycleSolveStatus.INSUFFICIENT_EXTERNAL_INPUT : CycleSolveStatus.SUCCESS,
            ExecutionCountKnowledge.EXACT, Arrays.asList(summary.counts), plan,
            Arrays.asList(exactExternalDemand), Arrays.asList(exactRequiredSeed), Arrays.asList(exactShortfall),
            Arrays.asList(bare.produced), Arrays.asList(exactDeliverable(model, actual.marking)), List.of(),
            explanation, metrics);
    }

    /** Exact prefix inventory for concrete input keys and normalized container/catalyst returns. */
    private static final class CircuitSummary {
        final PlannerAmount[] required;
        final PlannerAmount[] delta;
        final PlannerAmount[] produced;
        final PlannerAmount[] counts;

        CircuitSummary(PlannerAmount[] required, PlannerAmount[] delta, PlannerAmount[] produced,
                PlannerAmount[] counts) {
            this.required = required;
            this.delta = delta;
            this.produced = produced;
            this.counts = counts;
        }

        CircuitSummary then(CircuitSummary next) {
            PlannerAmount[] r = required.clone(), d = delta.clone(), p = produced.clone(), c = counts.clone();
            for (int i = 0; i < r.length; i++) {
                r[i] = r[i].max(next.required[i].subtract(d[i]));
                d[i] = d[i].add(next.delta[i]);
                p[i] = p[i].add(next.produced[i]);
            }
            for (int i = 0; i < c.length; i++) c[i] = c[i].add(next.counts[i]);
            return new CircuitSummary(r, d, p, c);
        }

        CircuitSummary repeated(PlannerAmount times) {
            if (times.signum() <= 0) throw new IllegalArgumentException("Circuit repetitions must be positive");
            PlannerAmount[] r = required.clone(), d = delta.clone(), p = produced.clone(), c = counts.clone();
            for (int i = 0; i < r.length; i++) {
                if (d[i].signum() < 0) r[i] = r[i].subtract(d[i].multiply(times.subtract(PlannerAmount.ONE)));
                d[i] = d[i].multiply(times);
                p[i] = p[i].multiply(times);
            }
            for (int i = 0; i < c.length; i++) c[i] = c[i].multiply(times);
            return new CircuitSummary(r, d, p, c);
        }
    }

    private static CircuitSummary emptySummary(Model model) {
        return new CircuitSummary(zeroes(model.keyCount()), zeroes(model.keyCount()),
            zeroes(model.keyCount()), zeroes(model.transitionCount()));
    }

    private static boolean dominates(PlannerAmount[] left, PlannerAmount[] right) {
        for (int i = 0; i < left.length; i++) if (left[i].compareTo(right[i]) > 0) return false;
        return true;
    }

    /** Alternative first firings, compared per concrete key rather than mixed-unit totals. */
    private static List<List<PlannerAmount>> startupCandidates(Model model, Cancellation cancellation,
            CycleMemoryBudget memory) throws InterruptedException {
        List<PlannerAmount[]> frontier = new ArrayList<>();
        for (int t = 0; t < model.transitionCount(); t++) {
            cancellation.checkpoint();
            PlannerAmount[] missing = zeroes(model.keyCount());
            for (int i = 0; i < missing.length; i++) {
                if (!model.suppliable[i]) {
                    missing[i] = PlannerAmount.of(model.cons[t][i]).subtract(model.stock[i]).max(PlannerAmount.ZERO);
                }
            }
            if (isZero(missing)) continue;
            boolean dominated = false;
            for (PlannerAmount[] existing : frontier) {
                cancellation.checkpoint();
                if (dominates(existing, missing)) { dominated = true; break; }
            }
            if (dominated) continue;
            var iterator = frontier.iterator();
            while (iterator.hasNext()) {
                cancellation.checkpoint();
                PlannerAmount[] existing = iterator.next();
                if (dominates(missing, existing)) {
                    iterator.remove();
                    memory.release(CycleMemoryBudget.markingBytes(existing));
                }
            }
            memory.retain(CycleMemoryBudget.markingBytes(missing));
            frontier.add(missing);
        }
        List<List<PlannerAmount>> candidates = new ArrayList<>();
        for (PlannerAmount[] missing : frontier) candidates.add(Arrays.asList(missing));
        return candidates;
    }

    /**
     * In-place {@code lap = summarizePlain(firing).then(lap)}, value-identical to the allocating composition. Keys the
     * transition neither consumes nor produces contribute zero deficit, delta and output, so they are left untouched.
     */
    private static void prependFiring(Model model, CircuitSummary lap, BatchFiring firing) {
        int t = firing.transition();
        PlannerAmount count = firing.exactCount();
        long[] cons = model.cons[t];
        long[] prod = model.prod[t];
        for (int key = 0; key < cons.length; key++) {
            long c = cons[key];
            long p = prod[key];
            if (c == 0L && p == 0L) continue;
            PlannerAmount runRequired = batchDeficit(PlannerAmount.ZERO, c, p, count);
            PlannerAmount runDelta = PlannerAmount.of(p).subtract(PlannerAmount.of(c)).multiply(count);
            lap.required[key] = runRequired.max(lap.required[key].subtract(runDelta));
            lap.delta[key] = runDelta.add(lap.delta[key]);
            lap.produced[key] = PlannerAmount.of(p).multiply(count).add(lap.produced[key]);
        }
        lap.counts[t] = count.add(lap.counts[t]);
    }

    private static CircuitSummary summarizePlain(Model model, List<BatchFiring> witness, int start, int end) {
        CircuitSummary summary = emptySummary(model);
        for (int index = start; index < end; index++) {
            BatchFiring firing = witness.get(index);
            CircuitSummary run = emptySummary(model);
            int t = firing.transition();
            for (int key = 0; key < model.keyCount(); key++) {
                run.required[key] = batchDeficit(PlannerAmount.ZERO, model.cons[t][key], model.prod[t][key],
                    firing.exactCount());
                run.delta[key] = PlannerAmount.of(model.prod[t][key]).subtract(PlannerAmount.of(model.cons[t][key]))
                    .multiply(firing.exactCount());
                run.produced[key] = PlannerAmount.of(model.prod[t][key]).multiply(firing.exactCount());
            }
            run.counts[t] = firing.exactCount();
            summary = summary.then(run);
        }
        return summary;
    }

    private static CircuitSummary summarize(Model model, List<BatchFiring> witness) {
        int[] ends = new int[witness.size()];
        for (int i = 0; i < ends.length; i++) ends[i] = i;
        int previousEnd = -1;
        for (int end = 0; end < witness.size(); end++) {
            BatchFiring last = witness.get(end);
            if (last.repetitions().compareTo(PlannerAmount.ONE) <= 0) continue;
            int start = end - last.repeatWidth() + 1;
            if (start < 0 || start <= previousEnd) throw new IllegalStateException("Overlapping circuit witness");
            ends[start] = end;
            previousEnd = end;
        }
        CircuitSummary result = emptySummary(model);
        for (int start = 0; start < ends.length;) {
            int end = ends[start];
            result = result.then(summarizePlain(model, witness, start, end + 1).repeated(witness.get(end).repetitions()));
            start = end + 1;
        }
        return result;
    }

    /** Bound all finite inputs, including non-component fuel. Boundary supplies are charged during final replay. */
    private static PlannerAmount safeRepetitions(Model model, PlannerAmount[] stock, CircuitSummary lap,
            PlannerAmount requested) {
        PlannerAmount result = requested;
        for (int i = 0; i < model.keyCount(); i++) {
            if (model.suppliable[i]) continue;
            if (stock[i].compareTo(lap.required[i]) < 0) return PlannerAmount.ZERO;
            if (lap.delta[i].signum() < 0) {
                PlannerAmount loss = PlannerAmount.ZERO.subtract(lap.delta[i]);
                result = result.min(stock[i].subtract(lap.required[i]).divide(loss).add(PlannerAmount.ONE));
            }
        }
        return result.max(PlannerAmount.ZERO);
    }

    private static PlannerAmount[] applyCircuit(Model model, PlannerAmount[] stock, CircuitSummary summary) {
        PlannerAmount[] next = stock.clone();
        for (int i = 0; i < next.length; i++) {
            if (model.suppliable[i]) next[i] = next[i].max(summary.required[i]);
            else if (next[i].compareTo(summary.required[i]) < 0) throw new IllegalStateException("Unseeded recipe circuit");
            next[i] = next[i].add(summary.delta[i]);
        }
        return next;
    }

    private static void repeatTail(List<BatchFiring> witness, int start, PlannerAmount extra) {
        int end = witness.size() - 1;
        BatchFiring last = witness.get(end);
        witness.set(end, new BatchFiring(last.transition(), last.exactCount(), end - start + 1,
            extra.add(PlannerAmount.ONE)));
    }

    /**
     * Reuse an actually executed recipe circuit, including split/merge recipes and returned containers.
     * This is a verified heuristic: competing seed routes still retain the original bounded search fallback.
     */
    private static PlannerAmount[] accelerateRecipeCircuit(Model model, PlannerAmount[] marking,
            List<BatchFiring> witness, Cancellation cancellation) throws InterruptedException {
        return accelerateRecipeCircuit(model, marking, witness, cancellation, null);
    }

    private static PlannerAmount[] accelerateRecipeCircuit(Model model, PlannerAmount[] marking,
            List<BatchFiring> witness, Cancellation cancellation, PlannerAmount[] remaining)
            throws InterruptedException {
        if (!model.circuitSafe) return marking;
        int end = witness.size();
        CircuitSummary lap = emptySummary(model);
        for (int start = end - 1; start >= 0; start--) {
            cancellation.checkpoint();
            if (witness.get(start).repetitions().compareTo(PlannerAmount.ONE) > 0) break;
            // Incremental suffix composition examines arbitrary circuit lengths in linear work.
            prependFiring(model, lap, witness.get(start));
            PlannerAmount requested = PlannerAmount.ZERO;
            for (int key = 0; key < model.keyCount(); key++) {
                if (lap.delta[key].signum() > 0 && model.required[key].compareTo(marking[key]) > 0) {
                    requested = requested.max(model.required[key].subtract(marking[key]).ceilDiv(lap.delta[key]));
                }
            }
            if (remaining != null) {
                requested = null;
                for (int t = 0; t < remaining.length; t++) {
                    if (lap.counts[t].signum() <= 0) continue;
                    PlannerAmount repetitions = remaining[t].divide(lap.counts[t]);
                    requested = requested == null ? repetitions : requested.min(repetitions);
                }
                if (requested == null) continue;
            }
            if (requested.signum() <= 0) continue;
            PlannerAmount extra = safeRepetitions(model, marking, lap, requested);
            if (extra.signum() <= 0) continue;
            repeatTail(witness, start, extra);
            if (remaining != null) {
                for (int t = 0; t < remaining.length; t++)
                    remaining[t] = remaining[t].subtract(lap.counts[t].multiply(extra));
            }
            return applyCircuit(model, marking, lap.repeated(extra));
        }
        return marking;
    }

    /**
     * Replays a witness in verified batches. Deficits on boundary keys are booked as imports, deficits on keys the
     * component has to own are booked as seed; both are recorded rather than allowed to go negative, so the pair
     * (seed, import) is exactly what the order needs to run. The deficit calculation is closed-form for a repeated
     * transition, so replay remains proportional to the number of macro-steps rather than the firing count.
     */
    private static Simulation simulate(Model model, PlannerAmount[] start, List<BatchFiring> witness) {
        int n = model.keyCount();
        PlannerAmount[] marking = Arrays.copyOf(start, n);
        PlannerAmount[] lazySeed = zeroes(n);
        PlannerAmount[] lazyImport = zeroes(n);
        PlannerAmount[] produced = zeroes(n);
        CircuitSummary summary = summarize(model, witness);
        for (int i = 0; i < n; i++) {
            PlannerAmount deficit = summary.required[i].subtract(marking[i]).max(PlannerAmount.ZERO);
            if (deficit.signum() > 0) {
                if (model.suppliable[i]) lazyImport[i] = deficit;
                else lazySeed[i] = deficit;
            }
            marking[i] = marking[i].add(deficit).add(summary.delta[i]);
            if (summary.produced[i].signum() > 0) produced[i] = summary.produced[i];
        }
        return new Simulation(marking, lazySeed, lazyImport, produced);
    }

    /** Minimum extra stock needed before a repeated transition can run without a deficit on this key. */
    private static PlannerAmount batchDeficit(PlannerAmount marking, long consumed, long produced,
            PlannerAmount count) {
        if (consumed <= 0L || count.signum() <= 0) return PlannerAmount.ZERO;
        PlannerAmount c = PlannerAmount.of(consumed);
        PlannerAmount p = PlannerAmount.of(produced);
        PlannerAmount requiredBeforeLast = produced >= consumed
            ? c
            : c.multiply(count).subtract(p.multiply(count.subtract(PlannerAmount.ONE)));
        return requiredBeforeLast.subtract(marking).max(PlannerAmount.ZERO);
    }

    private static int expandedWitnessLength(List<BatchFiring> witness) {
        PlannerAmount total = PlannerAmount.ZERO;
        for (int end = 0; end < witness.size(); end++) {
            BatchFiring firing = witness.get(end);
            total = total.add(firing.exactCount());
            if (firing.repetitions().compareTo(PlannerAmount.ONE) > 0) {
                PlannerAmount perLap = PlannerAmount.ZERO;
                for (int i = end - firing.repeatWidth() + 1; i <= end; i++) perLap = perLap.add(witness.get(i).exactCount());
                total = total.add(perLap.multiply(firing.repetitions().subtract(PlannerAmount.ONE)));
            }
            if (total.compareTo(PlannerAmount.of(MAX_EXPANDED_WITNESS)) > 0) return 0;
        }
        return (int) total.longValueExact();
    }

    private static PlannerAmount[] exactDeliverable(Model model, PlannerAmount[] marking) {
        PlannerAmount[] result = zeroes(model.keyCount());
        for (int i = 0; i < model.keyCount(); i++) {
            if (model.required[i].signum() > 0) result[i] = marking[i];
        }
        return result;
    }

    private static CycleSolveDiagnostic metrics(CycleSolveMetrics metrics) {
        return new CycleSolveDiagnostic(CycleSolveDiagnostic.Code.SEARCH_METRICS,
            "keys=" + metrics.relevantKeys() + " transitions=" + metrics.transitions()
                + " states=" + metrics.statesVisited() + " expanded=" + metrics.statesExpanded()
                + " witness=" + metrics.witnessLength() + " ladder=" + metrics.seedLadderSteps()
                + " greedyCandidates=" + metrics.greedyCandidates()
                + " lookaheadNodes=" + metrics.lookaheadNodes()
                + " heuristicMacroSteps=" + metrics.heuristicMacroSteps()
                + " heuristicBudgetExhausted=" + metrics.heuristicBudgetExhausted());
    }

    private static String describe(Model model, PlannerAmount[] amounts) {
        StringBuilder builder = new StringBuilder("{");
        boolean first = true;
        for (int i = 0; i < model.keyCount(); i++) {
            if (amounts[i].signum() <= 0) continue;
            if (!first) builder.append(", ");
            builder.append(amounts[i]).append(" x ").append(model.net.keyName(i));
            first = false;
        }
        return builder.append('}').toString();
    }

    private static boolean isZero(PlannerAmount[] values) {
        for (PlannerAmount value : values) if (value.signum() > 0) return false;
        return true;
    }

    private static PlannerAmount[] zeroes(int length) {
        PlannerAmount[] result = new PlannerAmount[length];
        Arrays.fill(result, PlannerAmount.ZERO);
        return result;
    }

    // ---------------------------------------------------------------------------------------------------
    // Internal data
    // ---------------------------------------------------------------------------------------------------

    private record BatchFiring(int transition, PlannerAmount exactCount, int repeatWidth, PlannerAmount repetitions) {
        private BatchFiring(int transition, PlannerAmount count) { this(transition, count, 1, PlannerAmount.ONE); }

        private BatchFiring {
            if (transition < 0) throw new IllegalArgumentException("Batch transition must not be negative");
            if (exactCount.signum() <= 0) throw new IllegalArgumentException("Batch firing count must be positive");
        }
    }

    private static final class Model {
        final CycleNet net;
        final long[][] cons;
        final long[][] prod;
        final boolean[] suppliable;
        final boolean[] producesRequired;
        final PlannerAmount[] stock;
        final PlannerAmount[] required;
        final TransitionMetadata[] metadata;
        final boolean[] consumedKeys;
        final boolean circuitSafe;

        Model(CycleNet net, long[][] cons, long[][] prod, boolean[] suppliable, boolean[] producesRequired,
                PlannerAmount[] stock, PlannerAmount[] required, TransitionMetadata[] metadata,
                boolean[] consumedKeys, boolean circuitSafe) {
            this.net = net;
            this.cons = cons;
            this.prod = prod;
            this.suppliable = suppliable;
            this.producesRequired = producesRequired;
            this.stock = stock;
            this.required = required;
            this.metadata = metadata;
            this.consumedKeys = consumedKeys;
            this.circuitSafe = circuitSafe;
        }

        int keyCount() { return net.keyCount(); }
        int transitionCount() { return net.transitionCount(); }
    }

    private static final class Simulation {
        final PlannerAmount[] marking;
        final PlannerAmount[] lazySeed;
        final PlannerAmount[] lazyImport;
        final PlannerAmount[] produced;

        Simulation(PlannerAmount[] marking, PlannerAmount[] lazySeed, PlannerAmount[] lazyImport,
                PlannerAmount[] produced) {
            this.marking = marking;
            this.lazySeed = lazySeed;
            this.lazyImport = lazyImport;
            this.produced = produced;
        }
    }

    /**
     * Node list with the queue's sort keys mirrored in primitive arrays, so heap comparisons read two longs and two
     * ints instead of chasing list, node and amount objects. The order is deficit, then depth, then index.
     */
    private static final class NodeList extends ArrayList<Node> {
        private long[] deficits = new long[64];
        private int[] depths = new int[64];
        private boolean anyWideDeficit;

        @Override public boolean add(Node node) {
            int index = size();
            if (index == deficits.length) {
                deficits = Arrays.copyOf(deficits, index * 2);
                depths = Arrays.copyOf(depths, index * 2);
            }
            if (node.deficit.fitsLong()) deficits[index] = node.deficit.longValueExact();
            else anyWideDeficit = true;
            depths[index] = node.depth;
            return super.add(node);
        }

        int compare(int left, int right) {
            int order = anyWideDeficit ? get(left).deficit.compareTo(get(right).deficit)
                : Long.compare(deficits[left], deficits[right]);
            if (order != 0) return order;
            order = Integer.compare(depths[left], depths[right]);
            return order != 0 ? order : Integer.compare(left, right);
        }
    }

    private static final class Node {
        private final PlannerAmount[] marking;
        private final int parent;
        private final BatchFiring firing;
        private final int depth;
        private final PlannerAmount deficit;
        private List<BatchFiring> compactWitness;

        private Node(PlannerAmount[] marking, int parent, BatchFiring firing, int depth, PlannerAmount deficit) {
            this.marking = marking;
            this.parent = parent;
            this.firing = firing;
            this.depth = depth;
            this.deficit = deficit;
        }
    }

    private record OutputTarget(int key, PlannerAmount delta) {}
    private record UnlockTarget(int key, PlannerAmount threshold, PlannerAmount delta) {}
    private record TransitionMetadata(int[] consumedInternalKeys, int[] changedKeys,
            OutputTarget[] requiredOutputs, UnlockTarget[] unlockTargets) {}

    private static final long[] EMPTY_BATCHES = new long[0];

    /** Invocation-local storage keeps candidates independent. */
    private static final class BatchCandidates {
        private long[] values = new long[8];
        private int size;

        void add(long value) {
            for (int i = 0; i < size; i++) if (values[i] == value) return;
            if (size == values.length) values = Arrays.copyOf(values, size * 2);
            values[size++] = value;
        }

        long[] sorted() {
            Arrays.sort(values, 0, size);
            return size == 0 ? EMPTY_BATCHES : Arrays.copyOf(values, size);
        }
    }

    private static final class Marking {
        private final PlannerAmount[] cells;
        private final int hash;

        private Marking(Model model, PlannerAmount[] cells) {
            this.cells = cells.clone();
            for (int i = 0; i < cells.length; i++) {
                if (!model.consumedKeys[i]) this.cells[i] = cells[i].min(model.required[i]);
            }
            // The polynomial Arrays.hashCode collides heavily on small count vectors ((1,0) vs (0,31)), which made
            // probing dominate the search. The set is never iterated, so the hash function does not affect results.
            long h = 0x632BE59BD9B4E019L;
            for (PlannerAmount cell : this.cells) h = (h + cell.hashCode()) * 0x9E3779B97F4A7C15L;
            h ^= h >>> 29;
            h *= 0xBF58476D1CE4E5B9L;
            h ^= h >>> 32;
            this.hash = (int) h;
        }

        @Override public boolean equals(Object other) {
            // Linear probing calls equals on every probed slot; the cached hash rejects nearly all of them cheaply.
            return other instanceof Marking marking && marking.hash == hash && Arrays.equals(cells, marking.cells);
        }

        @Override public int hashCode() { return hash; }
    }

    private static final class Search {
        private enum Kind { REACHED, EXHAUSTED, STATE_BUDGET, DEPTH_TRUNCATED }

        private Kind kind = Kind.EXHAUSTED;
        private List<BatchFiring> witness = List.of();
        private long statesVisited;
        private long statesExpanded;
        private long greedyCandidates;
        private long lookaheadNodes;
        private long heuristicMacroSteps;
        private boolean heuristicBudgetExhausted;
        private boolean stateBudgetExhausted;
        private boolean firingDepthTruncated;
        private PlannerAmount[] unblockDeficit;
        private int unblockRank = Integer.MAX_VALUE;
        private PlannerAmount unblockTotal = null;
        private int unblockTransition = Integer.MAX_VALUE;

        /**
         * Records the cheapest way to unblock a disabled transition, preferring one that actually produces a
         * required output. That candidate is a lower-bound proposal when no full-order static certificate is available.
         */
        private void considerUnblock(Model model, PlannerAmount[] marking, int transition) {
            int rank = model.producesRequired[transition] ? 0 : 1;

            long[] cons = model.cons[transition];
            PlannerAmount total = PlannerAmount.ZERO;
            for (int i = 0; i < cons.length; i++) {
                PlannerAmount required = PlannerAmount.of(cons[i]);
                if (cons[i] > 0 && !model.suppliable[i] && marking[i].compareTo(required) < 0) {
                    total = total.add(normalizedDeficit(required.subtract(marking[i]), required));
                }
            }
            if (total.signum() <= 0) return;
            PlannerAmount[] deficit = new PlannerAmount[cons.length];
            for (int i = 0; i < cons.length; i++) {
                PlannerAmount required = PlannerAmount.of(cons[i]);
                deficit[i] = cons[i] > 0 && !model.suppliable[i] && marking[i].compareTo(required) < 0
                    ? required.subtract(marking[i]) : PlannerAmount.ZERO;
            }
            if (rank > unblockRank) return;
            if (rank == unblockRank) {
                if (total.compareTo(unblockTotal) > 0) return;
                if (total.equals(unblockTotal) && transition >= unblockTransition) return;
            }
            unblockRank = rank;
            unblockTotal = total;
            unblockTransition = transition;
            unblockDeficit = deficit;
        }
    }
}
