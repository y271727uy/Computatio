package com.y271727uy.computatio.api.cycle;

import com.y271727uy.computatio.algorithm.amount.PlannerAmount;
import com.y271727uy.computatio.algorithm.cycle.BoundedCycleSolver;
import com.y271727uy.computatio.algorithm.cycle.CycleNet;
import com.y271727uy.computatio.algorithm.cycle.CycleRun;
import com.y271727uy.computatio.algorithm.cycle.CycleSolution;
import com.y271727uy.computatio.algorithm.cycle.CycleSolveDiagnostic;
import com.y271727uy.computatio.algorithm.cycle.CycleSolveMetrics;
import com.y271727uy.computatio.algorithm.cycle.PlanningBudget;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/**
 * One cyclic recipe component in the caller's own key and recipe types, turned into a cycle net by exactly the
 * rules NeoECO's {@code BoundedCycleSolver} applies to a {@code CycleSolveRequest}, with the answer mapped back.
 *
 * <ul>
 *   <li>Recipe views are ordered by id (stable) and deduplicated by recipe identity; the solver models the lowest-id
 *   view, while the single-recipe growth profile reads the first declared view, as NeoECO's growth calculator does.
 *   Every added view counts towards the declared-view budget.</li>
 *   <li>Keys are indexed as NeoECO indexes them: members, then each modelled view's inputs and gross outputs, then the
 *   required keys in the order they were added. Views of one recipe must touch the same keys.</li>
 *   <li>A boundary key is importable unless it is a member; an unbounded key is always importable; an import cap turns
 *   an importable key into finite stock. Stock and targets are passed raw, as NeoECO passes them.</li>
 * </ul>
 *
 * <p>{@code K} and {@code R} need stable {@code equals}/{@code hashCode}. Instances are immutable and thread-safe.
 */
public final class CycleProblem<K, R> {
    private final CycleNet net;
    private final List<K> keys;
    private final List<R> recipes;
    private final Map<K, Integer> keyIndex;
    private final Map<R, Integer> recipeIndex;

    private CycleProblem(CycleNet net, List<K> keys, List<R> recipes) {
        this.net = net;
        this.keys = List.copyOf(keys);
        this.recipes = List.copyOf(recipes);
        Map<K, Integer> byKey = new LinkedHashMap<>();
        for (int i = 0; i < keys.size(); i++) byKey.put(keys.get(i), i);
        Map<R, Integer> byRecipe = new LinkedHashMap<>();
        for (int i = 0; i < recipes.size(); i++) byRecipe.put(recipes.get(i), i);
        this.keyIndex = Collections.unmodifiableMap(byKey);
        this.recipeIndex = Collections.unmodifiableMap(byRecipe);
    }

    public static <K, R> Builder<K, R> builder() {
        return new Builder<>();
    }

    public List<K> keys() { return keys; }
    public List<R> recipes() { return recipes; }
    public int keyIndex(K key) { return keyIndex.getOrDefault(key, -1); }
    public int recipeIndex(R recipe) { return recipeIndex.getOrDefault(recipe, -1); }

    // ------------------------------------------------------------------------------------------------------------
    // Solving
    // ------------------------------------------------------------------------------------------------------------

    /**
     * NeoECO's {@code BoundedCycleSolver.solve}. Pass a {@link CycleBudget} to share one allowance with the caller's
     * own planning; any other check gets the default budget. Throws {@link CycleBudgetExhaustedException} when the
     * budget runs out, as NeoECO's solver lets its budget exhaustion through.
     */
    public CyclePlan<K, R> solve(CycleLimits limits, CycleCheck check) throws InterruptedException {
        try {
            return toPlan(new BoundedCycleSolver().solve(net, CycleLimits.internal(limits), Bridge.cancellation(check)));
        } catch (PlanningBudget.Exhausted exhausted) {
            throw Bridge.exhausted(exhausted);
        }
    }

    /**
     * NeoECO's {@code verifyFirings}: verifies a fixed firing candidate with prefix/repeat algebra, without optimizing
     * it again. Returns {@code null} when the component does not admit circuit algebra, a count is negative, or the
     * answer's firing counts do not equal {@code counts} exactly, so an explicit zero or a foreign recipe gives
     * {@code null}.
     */
    public CyclePlan<K, R> verifyFirings(Map<R, BigInteger> counts, CycleLimits limits, CycleCheck check)
            throws InterruptedException {
        Map<Integer, PlannerAmount> byIndex = new LinkedHashMap<>();
        int foreign = -1;
        for (var entry : counts.entrySet()) {
            int index = recipeIndex(entry.getKey());
            byIndex.put(index >= 0 ? index : foreign--, Bridge.amount(entry.getValue()));
        }
        try {
            CycleSolution solution = new BoundedCycleSolver().verifyFirings(net, byIndex, CycleLimits.internal(limits),
                Bridge.cancellation(check));
            return solution == null ? null : toPlan(solution);
        } catch (PlanningBudget.Exhausted exhausted) {
            throw Bridge.exhausted(exhausted);
        }
    }

    /**
     * {@link #solve} on {@code workerExecutor} with the default budget. Cancelling the future stops the solve at its
     * next checkpoint and completes it as {@link CycleStatus#CANCELLED}.
     */
    public CompletableFuture<CyclePlan<K, R>> solveAsync(CycleLimits limits, Executor workerExecutor) {
        Objects.requireNonNull(workerExecutor, "workerExecutor");
        CompletableFuture<CyclePlan<K, R>> future = new CompletableFuture<>();
        workerExecutor.execute(() -> {
            if (future.isDone()) return;
            CycleCheck onCancel = () -> {
                if (future.isCancelled()) throw new InterruptedException("Cycle solve was cancelled");
            };
            try {
                future.complete(solve(limits, CycleBudget.withDefaults(onCancel)));
            } catch (InterruptedException cancelled) {
                future.complete(toPlan(CycleSolution.cancelled()));
            } catch (Throwable failure) {
                future.completeExceptionally(failure instanceof CompletionException ? failure
                    : new CompletionException(failure));
            }
        });
        return future;
    }

    private CyclePlan<K, R> toPlan(CycleSolution solution) {
        List<CyclePlan.Run<R>> runs = new ArrayList<>(solution.plan().size());
        for (CycleRun run : solution.plan()) {
            runs.add(new CyclePlan.Run<>(recipes.get(run.transition()), run.count().toBigInteger(), run.repeatWidth(),
                run.plannerRepetitions().toBigInteger()));
        }
        List<R> witness = new ArrayList<>(solution.witness().size());
        for (int transition : solution.witness()) witness.add(recipes.get(transition));
        List<CyclePlan.Diagnostic> diagnostics = new ArrayList<>(solution.diagnostics().size());
        for (CycleSolveDiagnostic diagnostic : solution.diagnostics()) {
            diagnostics.add(new CyclePlan.Diagnostic(diagnostic.code().name(), diagnostic.message()));
        }
        List<Map<K, BigInteger>> candidates = new ArrayList<>();
        for (Map<Integer, PlannerAmount> candidate : solution.startupCandidates()) candidates.add(byKey(candidate));
        CycleSolveMetrics m = solution.metrics();
        return new CyclePlan<>(CycleStatus.valueOf(solution.status().name()), solution.hasExactExecutionCounts(),
            byRecipe(solution.firings()), byKey(solution.externalDemand()), byKey(solution.requiredSeed()),
            byKey(solution.seedShortfall()), byKey(solution.produced()), byKey(solution.deliverable()),
            List.copyOf(runs), List.copyOf(witness), List.copyOf(diagnostics),
            new CyclePlan.Metrics(m.relevantKeys(), m.transitions(), m.statesVisited(), m.statesExpanded(),
                m.witnessLength(), m.seedLadderSteps(), m.stateBudgetExhausted(), m.firingDepthTruncated(),
                m.amountOverflowTruncated(), m.greedyCandidates(), m.lookaheadNodes(), m.heuristicMacroSteps(),
                m.heuristicBudgetExhausted()),
            List.copyOf(candidates));
    }

    private Map<K, BigInteger> byKey(Map<Integer, PlannerAmount> values) {
        Map<K, BigInteger> result = new LinkedHashMap<>();
        values.forEach((key, amount) -> result.put(keys.get(key), amount.toBigInteger()));
        return result;
    }

    private Map<R, BigInteger> byRecipe(Map<Integer, PlannerAmount> values) {
        Map<R, BigInteger> result = new LinkedHashMap<>();
        values.forEach((transition, amount) -> result.put(recipes.get(transition), amount.toBigInteger()));
        return result;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Builder
    // ------------------------------------------------------------------------------------------------------------

    public static final class Builder<K, R> {
        private final List<K> members = new ArrayList<>();
        private final Set<K> boundary = new LinkedHashSet<>();
        private final Set<K> unbounded = new LinkedHashSet<>();
        private final Map<K, PlannerAmount> importCaps = new LinkedHashMap<>();
        private final Map<K, PlannerAmount> stock = new LinkedHashMap<>();
        private final Map<K, PlannerAmount> required = new LinkedHashMap<>();
        private final List<RecipeSpec<K, R>> views = new ArrayList<>();
        private boolean growthSelfLoop = true;

        private Builder() {}

        /** A key of the cyclic component itself, in declaration order. */
        public Builder<K, R> member(K key) { members.add(Objects.requireNonNull(key, "key")); return this; }

        /** A key named by the external resource boundary; importable unless it is a member. */
        public Builder<K, R> boundary(K key) { boundary.add(Objects.requireNonNull(key, "key")); return this; }

        /** A key with unlimited stock; always importable (NeoECO's {@code unboundedStock}). */
        public Builder<K, R> unbounded(K key) { unbounded.add(Objects.requireNonNull(key, "key")); return this; }

        /** Caps the total import of an importable key over the whole witness. */
        public Builder<K, R> importCap(K key, BigInteger cap) {
            if (cap.signum() < 0) throw new IllegalArgumentException("Negative boundary import cap");
            importCaps.put(Objects.requireNonNull(key, "key"), Bridge.amount(cap));
            return this;
        }

        public Builder<K, R> importCap(K key, long cap) { return importCap(key, BigInteger.valueOf(cap)); }

        /** Relevant stock, raw (NeoECO's {@code plannerRelevantStock}). */
        public Builder<K, R> stock(K key, BigInteger amount) {
            stock.put(Objects.requireNonNull(key, "key"), Bridge.amount(amount));
            return this;
        }

        public Builder<K, R> stock(K key, long amount) { return stock(key, BigInteger.valueOf(amount)); }

        /** Absolute on-hand target, raw (NeoECO's {@code plannerRequiredOutputs}); required keys are indexed in this order. */
        public Builder<K, R> required(K key, BigInteger amount) {
            required.put(Objects.requireNonNull(key, "key"), Bridge.amount(amount));
            return this;
        }

        public Builder<K, R> required(K key, long amount) { return required(key, BigInteger.valueOf(amount)); }

        /** Whether every internal edge of the component is a self-loop of its single recipe on its single member. */
        public Builder<K, R> growthSelfLoop(boolean value) { growthSelfLoop = value; return this; }

        /** Starts one recipe view; finish it with {@link RecipeSpec#add()}. */
        public RecipeSpec<K, R> recipe(R recipe, int id) {
            return new RecipeSpec<>(this, Objects.requireNonNull(recipe, "recipe"), id);
        }

        public CycleProblem<K, R> build() {
            // The solver models the lowest-id view; the growth calculator reads the first declared view.
            List<RecipeSpec<K, R>> sorted = new ArrayList<>(views);
            sorted.sort(Comparator.comparingInt(spec -> spec.id));
            Map<R, RecipeSpec<K, R>> modelled = new LinkedHashMap<>();
            for (RecipeSpec<K, R> spec : sorted) modelled.putIfAbsent(spec.recipe, spec);
            Map<R, RecipeSpec<K, R>> growthView = new LinkedHashMap<>();
            for (RecipeSpec<K, R> spec : views) growthView.putIfAbsent(spec.recipe, spec);
            List<RecipeSpec<K, R>> transitions = new ArrayList<>(modelled.values());

            LinkedHashMap<K, Integer> index = new LinkedHashMap<>();
            for (K member : members) index.putIfAbsent(member, index.size());
            for (RecipeSpec<K, R> spec : transitions) {
                for (Input<K> input : spec.inputs) index.putIfAbsent(input.key, index.size());
                for (Output<K> output : spec.outputs) index.putIfAbsent(output.key, index.size());
            }
            for (K key : required.keySet()) index.putIfAbsent(key, index.size());
            List<K> keys = new ArrayList<>(index.keySet());
            int n = keys.size();
            Set<K> memberSet = new LinkedHashSet<>(members);

            CycleNet.Builder net = CycleNet.builder(n).declaredTransitions(views.size()).growthSelfLoop(growthSelfLoop);
            for (int k = 0; k < n; k++) {
                K key = keys.get(k);
                net.keyName(k, String.valueOf(key));
                if (memberSet.contains(key)) net.member(k);
                if (boundary.contains(key)) net.boundary(k);
                if (unbounded.contains(key)) net.unbounded(k);
                if (importCaps.containsKey(key)) net.importCap(k, importCaps.get(key));
                net.stock(k, stock.getOrDefault(key, PlannerAmount.ZERO));
                net.required(k, required.getOrDefault(key, PlannerAmount.ZERO));
            }
            List<R> recipeOrder = new ArrayList<>();
            for (RecipeSpec<K, R> spec : transitions) {
                PlannerAmount[] cons = spec.consumed(index, n);
                PlannerAmount[] prod = spec.produced(index, n);
                RecipeSpec<K, R> growth = growthView.get(spec.recipe);
                int t = net.addTransition(String.valueOf(spec.id), cons, prod, spec.circuitSafe, growth.growthSafe);
                net.growthProfile(t, growth.consumed(index, n), growth.produced(index, n));
                net.growthRemainder(t, growth.remainder(index, n));
                if (spec.rejection != null) net.rejectTransition(t, spec.rejection);
                recipeOrder.add(spec.recipe);
            }
            return new CycleProblem<>(net.build(), keys, recipeOrder);
        }
    }

    /** One recipe view under construction. */
    public static final class RecipeSpec<K, R> {
        private final Builder<K, R> owner;
        private final R recipe;
        private final int id;
        private final List<Input<K>> inputs = new ArrayList<>();
        private final List<Output<K>> outputs = new ArrayList<>();
        private boolean circuitSafe = true;
        private boolean growthSafe = true;
        private String rejection;

        private RecipeSpec(Builder<K, R> owner, R recipe, int id) {
            this.owner = owner;
            this.recipe = recipe;
            this.id = id;
        }

        /** A consumed input per firing (repeats of a key are summed). */
        public RecipeSpec<K, R> input(K key, BigInteger amount) { return input(key, amount, null, null); }

        public RecipeSpec<K, R> input(K key, long amount) { return input(key, BigInteger.valueOf(amount)); }

        /** A consumed input whose slot hands {@code remainderAmount} of {@code remainderKey} back per firing. */
        public RecipeSpec<K, R> input(K key, BigInteger amount, K remainderKey, BigInteger remainderAmount) {
            inputs.add(new Input<>(Objects.requireNonNull(key, "key"), Bridge.amount(amount), remainderKey,
                remainderAmount == null ? PlannerAmount.ZERO : Bridge.amount(remainderAmount)));
            return this;
        }

        /** A gross output per firing: normal products and normalised returned stock alike. */
        public RecipeSpec<K, R> output(K key, long amount) {
            outputs.add(new Output<>(Objects.requireNonNull(key, "key"), amount));
            return this;
        }

        /** NeoECO: cycle-safe static semantics and no durability requirement. Default true. */
        public RecipeSpec<K, R> circuitSafe(boolean value) { circuitSafe = value; return this; }

        /** NeoECO's validated profile is NET_GROWTH_SAFE. Default true. */
        public RecipeSpec<K, R> growthSafe(boolean value) { growthSafe = value; return this; }

        /** NeoECO's {@code unsupportedReason} for this view. */
        public RecipeSpec<K, R> reject(String reason) { rejection = Objects.requireNonNull(reason, "reason"); return this; }

        public Builder<K, R> add() {
            owner.views.add(this);
            return owner;
        }

        private PlannerAmount[] consumed(Map<K, Integer> index, int n) {
            PlannerAmount[] result = zeroes(n);
            for (Input<K> input : inputs) {
                Integer slot = index.get(input.key);
                if (slot != null) result[slot] = result[slot].add(input.amount);
            }
            return result;
        }

        private PlannerAmount[] produced(Map<K, Integer> index, int n) {
            PlannerAmount[] result = zeroes(n);
            for (Output<K> output : outputs) {
                Integer slot = index.get(output.key);
                if (slot != null) result[slot] = result[slot].add(PlannerAmount.of(output.amount));
            }
            return result;
        }

        private PlannerAmount[] remainder(Map<K, Integer> index, int n) {
            PlannerAmount[] result = zeroes(n);
            for (Input<K> input : inputs) {
                if (input.remainderKey == null) continue;
                Integer slot = index.get(input.remainderKey);
                if (slot != null) result[slot] = result[slot].add(input.remainderAmount);
            }
            return result;
        }

        private static PlannerAmount[] zeroes(int n) {
            PlannerAmount[] values = new PlannerAmount[n];
            Arrays.fill(values, PlannerAmount.ZERO);
            return values;
        }
    }

    private record Input<K>(K key, PlannerAmount amount, K remainderKey, PlannerAmount remainderAmount) {}

    private record Output<K>(K key, long amount) {}
}
