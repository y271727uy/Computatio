package com.y271727uy.computatio.api.cycle;

import com.y271727uy.computatio.algorithm.amount.PlannerAmount;
import com.y271727uy.computatio.algorithm.cycle.BoundedCycleSolver;
import com.y271727uy.computatio.algorithm.cycle.Cancellation;
import com.y271727uy.computatio.algorithm.cycle.CycleNet;
import com.y271727uy.computatio.algorithm.cycle.CycleSolution;
import com.y271727uy.computatio.algorithm.cycle.CycleSolveLimits;
import com.y271727uy.computatio.algorithm.cycle.CycleSolveStatus;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One cyclic recipe component described in the caller's own types, turned into a {@link CycleNet} by exactly the
 * rules NeoECO's {@code BoundedCycleSolver} applies to a {@code CycleSolveRequest}, and the answer mapped back.
 *
 * <ul>
 *   <li>Recipes are ordered by id (stable) and deduplicated by identity ({@code R.equals}); the first view wins, and
 *   every added view still counts towards the declared-view budget.</li>
 *   <li>Keys are indexed as NeoECO indexes them: members, then each recipe's inputs and gross outputs in order, then
 *   the required keys in the order they were added.</li>
 *   <li>A boundary key is importable unless it is also a member. Stock and targets are clamped at zero.</li>
 *   <li>A recipe total beyond the long range, a caller rejection and input remainders reach the solver at the same
 *   point and with the same budget use as in NeoECO.</li>
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

    /** The net handed to the solver; key {@code i} is {@code keys().get(i)}, transition {@code t} is {@code recipes().get(t)}. */
    public CycleNet net() { return net; }
    public List<K> keys() { return keys; }
    public List<R> recipes() { return recipes; }
    public int keyIndex(K key) { return keyIndex.getOrDefault(key, -1); }
    public int recipeIndex(R recipe) { return recipeIndex.getOrDefault(recipe, -1); }
    public R recipe(int transition) { return recipes.get(transition); }
    public K key(int index) { return keys.get(index); }

    // ------------------------------------------------------------------------------------------------------------
    // Solving
    // ------------------------------------------------------------------------------------------------------------

    /** Full solve (NeoECO's {@code BoundedCycleSolver.solve}). A plain cancellation gets the default budget. */
    public CycleSolution solve(CycleSolveLimits limits, Cancellation cancellation) throws InterruptedException {
        return new BoundedCycleSolver().solve(net, limits, cancellation == null ? Cancellation.NONE : cancellation);
    }

    /** Single-recipe growth path only (NeoECO's {@code solveGrowth}); {@code null} when it does not apply. */
    public CycleSolution solveGrowth(CycleSolveLimits limits, Cancellation cancellation) throws InterruptedException {
        return new BoundedCycleSolver().solveGrowth(net, limits, cancellation == null ? Cancellation.NONE : cancellation);
    }

    /**
     * NeoECO's {@code verifyFirings} with a recipe-keyed candidate. The answer is returned only when its positive
     * firing counts equal {@code counts} exactly; an explicit zero, or a recipe outside this component, gives
     * {@code null}, as in NeoECO.
     */
    public CycleSolution verifyFirings(Map<R, PlannerAmount> counts, CycleSolveLimits limits, Cancellation cancellation)
            throws InterruptedException {
        Map<Integer, PlannerAmount> byIndex = new LinkedHashMap<>();
        int foreign = -1;
        for (var entry : counts.entrySet()) {
            int index = recipeIndex(entry.getKey());
            // A recipe outside the component can never be in the answer, so it gets a key no transition has.
            byIndex.put(index >= 0 ? index : foreign--, entry.getValue());
        }
        return new BoundedCycleSolver().verifyFirings(net, byIndex, limits == null ? CycleSolveLimits.DEFAULT : limits,
            cancellation == null ? Cancellation.NONE : cancellation);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Answer in the caller's types
    // ------------------------------------------------------------------------------------------------------------

    /**
     * The status a long-based caller reports: a SUCCESS or INSUFFICIENT_EXTERNAL_INPUT answer whose amounts leave the
     * long range becomes {@link CycleSolveStatus#UNREPRESENTABLE}, as NeoECO's own result does.
     */
    public static CycleSolveStatus longStatus(CycleSolution solution) {
        if (solution.exceedsLongRange() && (solution.status() == CycleSolveStatus.SUCCESS
                || solution.status() == CycleSolveStatus.INSUFFICIENT_EXTERNAL_INPUT)) {
            return CycleSolveStatus.UNREPRESENTABLE;
        }
        return solution.status();
    }

    /** Firing count per recipe, only recipes that fire (NeoECO's {@code exactPatternTimes}). */
    public Map<R, PlannerAmount> firings(CycleSolution solution) {
        Map<R, PlannerAmount> result = new LinkedHashMap<>();
        for (int t = 0; t < solution.firings().size(); t++) {
            if (solution.firings().get(t).signum() > 0) result.put(recipes.get(t), solution.firings().get(t));
        }
        return result;
    }

    public Map<K, PlannerAmount> externalDemand(CycleSolution solution) { return positive(solution.externalDemand()); }
    public Map<K, PlannerAmount> requiredSeed(CycleSolution solution) { return positive(solution.requiredSeed()); }
    public Map<K, PlannerAmount> seedShortfall(CycleSolution solution) { return positive(solution.seedShortfall()); }
    public Map<K, PlannerAmount> produced(CycleSolution solution) { return positive(solution.produced()); }

    /** On-hand amount of every required key after the plan, zeros included (NeoECO's {@code deliverableOutputs}). */
    public Map<K, PlannerAmount> deliverable(CycleSolution solution) {
        Map<K, PlannerAmount> result = new LinkedHashMap<>();
        if (solution.deliverable().isEmpty()) return result;
        for (int k = 0; k < keys.size(); k++) {
            if (net.required(k).signum() > 0) result.put(keys.get(k), solution.deliverable().get(k));
        }
        return result;
    }

    /** Alternative start-up seeds, each holding only the keys it needs. */
    public List<Map<K, PlannerAmount>> startupCandidates(CycleSolution solution) {
        List<Map<K, PlannerAmount>> result = new ArrayList<>();
        for (List<PlannerAmount> candidate : solution.startupCandidates()) result.add(positive(candidate));
        return result;
    }

    private Map<K, PlannerAmount> positive(List<PlannerAmount> amounts) {
        Map<K, PlannerAmount> result = new LinkedHashMap<>();
        for (int k = 0; k < amounts.size(); k++) {
            if (amounts.get(k).signum() > 0) result.put(keys.get(k), amounts.get(k));
        }
        return result;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Builder
    // ------------------------------------------------------------------------------------------------------------

    public static final class Builder<K, R> {
        private final List<K> members = new ArrayList<>();
        private final Set<K> boundary = new LinkedHashSet<>();
        private final Map<K, Long> stock = new LinkedHashMap<>();
        private final Map<K, PlannerAmount> required = new LinkedHashMap<>();
        private final List<RecipeSpec<K, R>> recipes = new ArrayList<>();
        private String keyNames;

        private Builder() {}

        /** A key of the cyclic component itself, in declaration order. */
        public Builder<K, R> member(K key) {
            members.add(Objects.requireNonNull(key, "key"));
            return this;
        }

        /** A key the rest of the dependency graph can supply; ignored for members. */
        public Builder<K, R> boundary(K key) {
            boundary.add(Objects.requireNonNull(key, "key"));
            return this;
        }

        /** Relevant stock; negative values count as zero. */
        public Builder<K, R> stock(K key, long amount) {
            stock.put(Objects.requireNonNull(key, "key"), amount);
            return this;
        }

        /** Absolute on-hand target; negative values count as zero. Required keys are indexed in this order. */
        public Builder<K, R> required(K key, PlannerAmount amount) {
            required.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(amount, "amount"));
            return this;
        }

        public Builder<K, R> required(K key, long amount) {
            return required(key, PlannerAmount.of(amount));
        }

        /** Starts one recipe view; finish it with {@link RecipeSpec#add()}. */
        public RecipeSpec<K, R> recipe(R recipe, int id) {
            return new RecipeSpec<>(this, Objects.requireNonNull(recipe, "recipe"), id);
        }

        public CycleProblem<K, R> build() {
            // Stable id order, first view per recipe identity wins (NeoECO's dedup).
            List<RecipeSpec<K, R>> sorted = new ArrayList<>(recipes);
            sorted.sort(Comparator.comparingInt(spec -> spec.id));
            Map<R, RecipeSpec<K, R>> unique = new LinkedHashMap<>();
            for (RecipeSpec<K, R> spec : sorted) unique.putIfAbsent(spec.recipe, spec);
            List<RecipeSpec<K, R>> transitions = new ArrayList<>(unique.values());

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

            CycleNet.Builder net = CycleNet.builder(n).declaredTransitions(recipes.size());
            for (int k = 0; k < n; k++) {
                K key = keys.get(k);
                net.keyName(k, String.valueOf(key));
                if (memberSet.contains(key)) net.member(k);
                else if (boundary.contains(key)) net.boundary(k);
                net.stock(k, Math.max(0L, stock.getOrDefault(key, 0L)));
                net.required(k, required.getOrDefault(key, PlannerAmount.ZERO).max(PlannerAmount.ZERO));
            }
            List<R> recipeOrder = new ArrayList<>();
            for (RecipeSpec<K, R> spec : transitions) {
                PlannerAmount[] cons = zeroes(n);
                PlannerAmount[] prod = zeroes(n);
                PlannerAmount[] remainder = zeroes(n);
                for (Input<K> input : spec.inputs) {
                    int slot = index.get(input.key);
                    cons[slot] = cons[slot].add(input.amount);
                    if (input.remainderKey != null && index.containsKey(input.remainderKey)) {
                        int back = index.get(input.remainderKey);
                        remainder[back] = remainder[back].add(input.remainderAmount);
                    }
                }
                for (Output<K> output : spec.outputs) {
                    int slot = index.get(output.key);
                    prod[slot] = prod[slot].add(PlannerAmount.of(output.amount));
                }
                long[] consumed = new long[n];
                long[] produced = new long[n];
                String overflow = null;
                for (int k = 0; k < n && overflow == null; k++) {
                    if (!cons[k].fitsLong()) {
                        overflow = "input total for " + keys.get(k) + " amount=" + cons[k] + " max=" + Long.MAX_VALUE;
                    } else if (!prod[k].fitsLong()) {
                        overflow = "output total for " + keys.get(k) + " amount=" + prod[k] + " max=" + Long.MAX_VALUE;
                    } else {
                        consumed[k] = cons[k].longValueExact();
                        produced[k] = prod[k].longValueExact();
                    }
                }
                if (overflow != null) {
                    // The row cannot be built; the solver reports the overflow in its matrix pass, as NeoECO does.
                    // Only "which keys are produced" is read before that pass (the no-output check), so keep exactly that.
                    for (int k = 0; k < n; k++) {
                        consumed[k] = 0L;
                        produced[k] = prod[k].signum() > 0 ? 1L : 0L;
                    }
                }
                long[] returned = new long[n];
                boolean growthSafe = spec.growthSafe;
                for (Input<K> input : spec.inputs) {
                    // NeoECO's profile validator: a named remainder must be a positive amount (INDETERMINATE_INPUT).
                    if (input.remainderKey != null && input.remainderAmount.signum() <= 0) growthSafe = false;
                }
                for (int k = 0; k < n; k++) {
                    if (remainder[k].fitsLong() && remainder[k].signum() >= 0) returned[k] = remainder[k].longValueExact();
                    else growthSafe = false; // NeoECO's profile validator rejects remainders beyond the long range
                }
                int t = net.addTransition(String.valueOf(spec.id), consumed, produced, spec.circuitSafe, growthSafe);
                net.growthRemainder(t, returned);
                if (spec.rejection != null) net.rejectTransition(t, spec.rejection);
                if (overflow != null) net.unrepresentableTotals(t, overflow);
                recipeOrder.add(spec.recipe);
            }
            return new CycleProblem<>(net.build(), keys, recipeOrder);
        }

        private static PlannerAmount[] zeroes(int n) {
            PlannerAmount[] values = new PlannerAmount[n];
            java.util.Arrays.fill(values, PlannerAmount.ZERO);
            return values;
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

        /** A consumed input per firing (may repeat a key; repeats are summed). */
        public RecipeSpec<K, R> input(K key, PlannerAmount amount) {
            inputs.add(new Input<>(Objects.requireNonNull(key, "key"), Objects.requireNonNull(amount, "amount"), null,
                PlannerAmount.ZERO));
            return this;
        }

        public RecipeSpec<K, R> input(K key, long amount) {
            return input(key, PlannerAmount.of(amount));
        }

        /** A consumed input whose slot hands {@code remainderAmount} of {@code remainderKey} back per firing. */
        public RecipeSpec<K, R> input(K key, PlannerAmount amount, K remainderKey, PlannerAmount remainderAmount) {
            inputs.add(new Input<>(Objects.requireNonNull(key, "key"), Objects.requireNonNull(amount, "amount"),
                remainderKey, remainderAmount == null ? PlannerAmount.ZERO : remainderAmount));
            return this;
        }

        /**
         * A gross output per firing: normal products and normalised returned stock alike. NeoECO's no-output rejection
         * looks at normal products only, so a recipe with returned stock but no normal product must also be rejected
         * with {@link #reject(String) reject("NO_OUTPUTS")}.
         */
        public RecipeSpec<K, R> output(K key, long amount) {
            outputs.add(new Output<>(Objects.requireNonNull(key, "key"), amount));
            return this;
        }

        /** The static contract allows circuit algebra (NeoECO: cycle-safe semantics and no durability). Default true. */
        public RecipeSpec<K, R> circuitSafe(boolean value) {
            circuitSafe = value;
            return this;
        }

        /** NeoECO's validated profile is NET_GROWTH_SAFE. Default true. */
        public RecipeSpec<K, R> growthSafe(boolean value) {
            growthSafe = value;
            return this;
        }

        /** The recipe cannot be modelled (NeoECO's {@code unsupportedReason}); see {@link CycleNet.Builder#rejectTransition}. */
        public RecipeSpec<K, R> reject(String reason) {
            rejection = Objects.requireNonNull(reason, "reason");
            return this;
        }

        public Builder<K, R> add() {
            owner.recipes.add(this);
            return owner;
        }
    }

    private record Input<K>(K key, PlannerAmount amount, K remainderKey, PlannerAmount remainderAmount) {}

    private record Output<K>(K key, long amount) {}
}
