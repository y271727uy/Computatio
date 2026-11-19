package com.y271727uy.computatio.algorithm.cycle;

import com.y271727uy.computatio.algorithm.amount.PlannerAmount;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Immutable description of one cyclic component, read as a small Petri net: exactly the information NeoECO's
 * {@code BoundedCycleSolver} reads from a {@code CycleSolveRequest}, indexed by key and transition number.
 *
 * <p>Keys must already be numbered the way NeoECO indexes them (members, then each transition's inputs and gross
 * outputs, then the required keys) and transitions must already be deduplicated and ordered by pattern id;
 * {@code api.cycle.CycleProblem} does both. Per key:
 * <ul>
 *   <li>{@code boundary}: named by the external resource boundary; importable unless it is a member.</li>
 *   <li>{@code unbounded}: unlimited stock; always importable (NeoECO's {@code unboundedStock}).</li>
 *   <li>{@code importCap}: a cap on a boundary import over the whole witness; such a key is modelled as finite stock.</li>
 *   <li>{@code stock} / {@code required}: the request's raw amounts (negative values are clamped by the solver, but
 *   the single-recipe growth calculation reads them raw, as NeoECO does).</li>
 * </ul>
 * Per transition: gross per-firing consumption and production, {@code circuitSafe} (cycle-safe semantics without
 * durability), {@code growthSafe} (NeoECO's validated NET_GROWTH_SAFE profile), {@code growthRemainder} (input
 * remainders the growth profile counts), an optional growth profile (the consumption and gross production of the
 * recipe view NeoECO's growth calculator reads: the first declared view, which may differ from the lowest-id view the
 * solver models; it defaults to the transition's own vectors), and an optional caller {@code rejection} (NeoECO's
 * {@code unsupportedReason}).
 * {@code declaredTransitionCount} is the number of recipe views before deduplication and {@code growthSelfLoop}
 * states that the component's internal edges are all self-loops of its single recipe on its single member.
 */
public final class CycleNet {
    private final int keyCount;
    private final String[] keyNames;
    private final boolean[] boundary;
    private final boolean[] unbounded;
    private final boolean[] member;
    private final PlannerAmount[] importCap;
    private final PlannerAmount[] stock;
    private final PlannerAmount[] required;
    private final List<String> transitionNames;
    private final PlannerAmount[][] consumed;
    private final PlannerAmount[][] produced;
    private final boolean[] circuitSafe;
    private final boolean[] growthSafe;
    private final String[] rejection;
    private final PlannerAmount[][] growthRemainder;
    private final PlannerAmount[][] growthConsumed;
    private final PlannerAmount[][] growthProduced;
    private final int declaredTransitionCount;
    private final boolean growthSelfLoop;

    private CycleNet(Builder builder) {
        int t = builder.transitionNames.size();
        this.keyCount = builder.keyCount;
        this.keyNames = builder.keyNames.clone();
        this.boundary = builder.boundary.clone();
        this.unbounded = builder.unbounded.clone();
        this.member = builder.member.clone();
        this.importCap = builder.importCap.clone();
        this.stock = builder.stock.clone();
        this.required = builder.required.clone();
        this.transitionNames = List.copyOf(builder.transitionNames);
        this.consumed = builder.consumed.stream().map(PlannerAmount[]::clone).toArray(PlannerAmount[][]::new);
        this.produced = builder.produced.stream().map(PlannerAmount[]::clone).toArray(PlannerAmount[][]::new);
        this.growthRemainder = builder.growthRemainder.stream().map(PlannerAmount[]::clone).toArray(PlannerAmount[][]::new);
        this.growthConsumed = builder.growthConsumed.stream().map(PlannerAmount[]::clone).toArray(PlannerAmount[][]::new);
        this.growthProduced = builder.growthProduced.stream().map(PlannerAmount[]::clone).toArray(PlannerAmount[][]::new);
        this.circuitSafe = new boolean[t];
        this.growthSafe = new boolean[t];
        for (int i = 0; i < t; i++) {
            circuitSafe[i] = builder.circuitSafe.get(i);
            growthSafe[i] = builder.growthSafe.get(i);
        }
        this.rejection = builder.rejection.toArray(new String[0]);
        this.declaredTransitionCount = Math.max(builder.declaredTransitionCount, t);
        this.growthSelfLoop = builder.growthSelfLoop;
    }

    public static Builder builder(int keyCount) {
        return new Builder(keyCount);
    }

    public int keyCount() { return keyCount; }
    public int transitionCount() { return transitionNames.size(); }
    public String keyName(int key) { return keyNames[key]; }
    public String transitionName(int transition) { return transitionNames.get(transition); }
    public boolean boundary(int key) { return boundary[key]; }
    public boolean unbounded(int key) { return unbounded[key]; }
    public boolean member(int key) { return member[key]; }
    /** The import cap of a key, or {@code null} when it has none. */
    public PlannerAmount importCap(int key) { return importCap[key]; }
    public PlannerAmount stock(int key) { return stock[key]; }
    public PlannerAmount required(int key) { return required[key]; }
    public PlannerAmount consumed(int transition, int key) { return consumed[transition][key]; }
    public PlannerAmount produced(int transition, int key) { return produced[transition][key]; }
    public boolean circuitSafe(int transition) { return circuitSafe[transition]; }
    public boolean growthSafe(int transition) { return growthSafe[transition]; }
    public String rejection(int transition) { return rejection[transition]; }
    public PlannerAmount growthRemainder(int transition, int key) { return growthRemainder[transition][key]; }
    public PlannerAmount growthConsumed(int transition, int key) { return growthConsumed[transition][key]; }
    public PlannerAmount growthProduced(int transition, int key) { return growthProduced[transition][key]; }
    public int declaredTransitionCount() { return declaredTransitionCount; }
    public boolean growthSelfLoop() { return growthSelfLoop; }

    /** Same structure, a different inventory snapshot. */
    public CycleNet withStock(PlannerAmount[] newStock) {
        Builder builder = toBuilder();
        for (int key = 0; key < keyCount; key++) builder.stock(key, newStock[key]);
        return builder.build();
    }

    /** Same structure, a different target. */
    public CycleNet withRequired(PlannerAmount[] newRequired) {
        Builder builder = toBuilder();
        for (int key = 0; key < keyCount; key++) builder.required(key, newRequired[key]);
        return builder.build();
    }

    private Builder toBuilder() {
        Builder builder = new Builder(keyCount);
        for (int key = 0; key < keyCount; key++) {
            builder.keyName(key, keyNames[key]).stock(key, stock[key]).required(key, required[key]);
            if (boundary[key]) builder.boundary(key);
            if (unbounded[key]) builder.unbounded(key);
            if (member[key]) builder.member(key);
            if (importCap[key] != null) builder.importCap(key, importCap[key]);
        }
        for (int t = 0; t < transitionCount(); t++) {
            builder.addTransition(transitionNames.get(t), consumed[t], produced[t], circuitSafe[t], growthSafe[t]);
            builder.growthRemainder(t, growthRemainder[t]);
            builder.growthProfile(t, growthConsumed[t], growthProduced[t]);
            if (rejection[t] != null) builder.rejectTransition(t, rejection[t]);
        }
        return builder.declaredTransitions(declaredTransitionCount).growthSelfLoop(growthSelfLoop);
    }

    public static final class Builder {
        private final int keyCount;
        private final String[] keyNames;
        private final boolean[] boundary;
        private final boolean[] unbounded;
        private final boolean[] member;
        private final PlannerAmount[] importCap;
        private final PlannerAmount[] stock;
        private final PlannerAmount[] required;
        private final List<String> transitionNames = new ArrayList<>();
        private final List<PlannerAmount[]> consumed = new ArrayList<>();
        private final List<PlannerAmount[]> produced = new ArrayList<>();
        private final List<Boolean> circuitSafe = new ArrayList<>();
        private final List<Boolean> growthSafe = new ArrayList<>();
        private final List<String> rejection = new ArrayList<>();
        private final List<PlannerAmount[]> growthRemainder = new ArrayList<>();
        private final List<PlannerAmount[]> growthConsumed = new ArrayList<>();
        private final List<PlannerAmount[]> growthProduced = new ArrayList<>();
        private int declaredTransitionCount;
        private boolean growthSelfLoop = true;

        private Builder(int keyCount) {
            if (keyCount < 0) throw new IllegalArgumentException("Key count must not be negative");
            this.keyCount = keyCount;
            this.keyNames = new String[keyCount];
            for (int key = 0; key < keyCount; key++) keyNames[key] = "#" + key;
            this.boundary = new boolean[keyCount];
            this.unbounded = new boolean[keyCount];
            this.member = new boolean[keyCount];
            this.importCap = new PlannerAmount[keyCount];
            this.stock = zeroes(keyCount);
            this.required = zeroes(keyCount);
        }

        public Builder keyName(int key, String name) {
            keyNames[checkKey(key)] = name == null ? "#" + key : name;
            return this;
        }

        public Builder boundary(int key) { boundary[checkKey(key)] = true; return this; }
        public Builder unbounded(int key) { unbounded[checkKey(key)] = true; return this; }
        public Builder member(int key) { member[checkKey(key)] = true; return this; }

        public Builder importCap(int key, PlannerAmount cap) {
            Objects.requireNonNull(cap, "cap");
            if (cap.signum() < 0) throw new IllegalArgumentException("Negative boundary import cap");
            importCap[checkKey(key)] = cap;
            return this;
        }

        public Builder stock(int key, long amount) { return stock(key, PlannerAmount.of(amount)); }
        public Builder stock(int key, PlannerAmount amount) { stock[checkKey(key)] = Objects.requireNonNull(amount); return this; }
        public Builder required(int key, long amount) { return required(key, PlannerAmount.of(amount)); }
        public Builder required(int key, PlannerAmount amount) { required[checkKey(key)] = Objects.requireNonNull(amount); return this; }

        /** Adds a circuit-safe, growth-safe transition and returns its index. */
        public int addTransition(String name, long[] consumedPerFiring, long[] producedPerFiring) {
            return addTransition(name, consumedPerFiring, producedPerFiring, true, true);
        }

        public int addTransition(String name, long[] consumedPerFiring, long[] producedPerFiring,
                boolean circuitSafeTransition, boolean growthSafeTransition) {
            return addTransition(name, amounts(consumedPerFiring), amounts(producedPerFiring), circuitSafeTransition,
                growthSafeTransition);
        }

        /** Adds a transition and returns its index; vectors are copied and indexed by key. */
        public int addTransition(String name, PlannerAmount[] consumedPerFiring, PlannerAmount[] producedPerFiring,
                boolean circuitSafeTransition, boolean growthSafeTransition) {
            if (consumedPerFiring.length != keyCount || producedPerFiring.length != keyCount) {
                throw new IllegalArgumentException("Transition vectors must have one entry per key");
            }
            int index = transitionNames.size();
            transitionNames.add(name == null ? "t" + index : name);
            consumed.add(consumedPerFiring.clone());
            produced.add(producedPerFiring.clone());
            circuitSafe.add(circuitSafeTransition);
            growthSafe.add(growthSafeTransition);
            rejection.add(null);
            growthRemainder.add(zeroes(keyCount));
            growthConsumed.add(consumedPerFiring.clone());
            growthProduced.add(producedPerFiring.clone());
            return index;
        }

        public Builder growthRemainder(int transition, long[] remainderPerFiring) {
            return growthRemainder(transition, amounts(remainderPerFiring));
        }

        public Builder growthRemainder(int transition, PlannerAmount[] remainderPerFiring) {
            if (remainderPerFiring.length != keyCount) throw new IllegalArgumentException("One remainder entry per key");
            growthRemainder.set(transition, remainderPerFiring.clone());
            return this;
        }

        /** Consumption and gross production of the view NeoECO's growth calculator reads (its first declared view). */
        public Builder growthProfile(int transition, PlannerAmount[] consumedPerFiring, PlannerAmount[] grossProducedPerFiring) {
            if (consumedPerFiring.length != keyCount || grossProducedPerFiring.length != keyCount) {
                throw new IllegalArgumentException("Growth profile vectors must have one entry per key");
            }
            growthConsumed.set(transition, consumedPerFiring.clone());
            growthProduced.set(transition, grossProducedPerFiring.clone());
            return this;
        }

        /** NeoECO's {@code unsupportedReason}: the solver rejects the component at the same point of preparation. */
        public Builder rejectTransition(int transition, String reason) {
            rejection.set(transition, Objects.requireNonNull(reason, "reason"));
            return this;
        }

        /** Number of recipe views before deduplication; values below the transition count are raised to it. */
        public Builder declaredTransitions(int count) {
            if (count < 0) throw new IllegalArgumentException("Declared transition count must not be negative");
            declaredTransitionCount = count;
            return this;
        }

        /** Whether every internal edge is a self-loop of the single recipe on the single member. Default true. */
        public Builder growthSelfLoop(boolean value) {
            growthSelfLoop = value;
            return this;
        }

        public CycleNet build() {
            return new CycleNet(this);
        }

        private int checkKey(int key) {
            if (key < 0 || key >= keyCount) throw new IndexOutOfBoundsException("key " + key);
            return key;
        }

        private PlannerAmount[] amounts(long[] values) {
            if (values.length != keyCount) throw new IllegalArgumentException("Vectors must have one entry per key");
            PlannerAmount[] result = new PlannerAmount[values.length];
            for (int i = 0; i < values.length; i++) result[i] = PlannerAmount.of(values[i]);
            return result;
        }

        private static PlannerAmount[] zeroes(int length) {
            PlannerAmount[] result = new PlannerAmount[length];
            Arrays.fill(result, PlannerAmount.ZERO);
            return result;
        }
    }
}
