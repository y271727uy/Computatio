package com.y271727uy.computatio.algorithm.cycle;

import com.y271727uy.computatio.algorithm.amount.PlannerAmount;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

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
    private final Row[] consumed;
    private final Row[] produced;
    private final boolean[] circuitSafe;
    private final boolean[] growthSafe;
    private final String[] rejection;
    private final Row[] growthRemainder;
    private final Row[] growthConsumed;
    private final Row[] growthProduced;
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
        // Rows are immutable, so the net shares them with its builder instead of copying.
        this.consumed = builder.consumed.toArray(new Row[0]);
        this.produced = builder.produced.toArray(new Row[0]);
        this.growthRemainder = builder.growthRemainder.toArray(new Row[0]);
        this.growthConsumed = builder.growthConsumed.toArray(new Row[0]);
        this.growthProduced = builder.growthProduced.toArray(new Row[0]);
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
    public PlannerAmount consumed(int transition, int key) { return consumed[transition].get(key); }
    public PlannerAmount produced(int transition, int key) { return produced[transition].get(key); }

    /** Writes the transition's non-zero consumption into {@code row}, indexed by key; other entries stay as they are. */
    public void copyConsumed(int transition, PlannerAmount[] row) { consumed[transition].copyInto(row); }

    /** Writes the transition's non-zero gross production into {@code row}, indexed by key. */
    public void copyProduced(int transition, PlannerAmount[] row) { produced[transition].copyInto(row); }
    public boolean circuitSafe(int transition) { return circuitSafe[transition]; }
    public boolean growthSafe(int transition) { return growthSafe[transition]; }
    public String rejection(int transition) { return rejection[transition]; }
    public PlannerAmount growthRemainder(int transition, int key) { return growthRemainder[transition].get(key); }
    public PlannerAmount growthConsumed(int transition, int key) { return growthConsumed[transition].get(key); }
    public PlannerAmount growthProduced(int transition, int key) { return growthProduced[transition].get(key); }
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
            builder.growthRemainder.set(t, growthRemainder[t]);
            builder.growthConsumed.set(t, growthConsumed[t]);
            builder.growthProduced.set(t, growthProduced[t]);
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
        private final List<Row> consumed = new ArrayList<>();
        private final List<Row> produced = new ArrayList<>();
        private final List<Boolean> circuitSafe = new ArrayList<>();
        private final List<Boolean> growthSafe = new ArrayList<>();
        private final List<String> rejection = new ArrayList<>();
        private final List<Row> growthRemainder = new ArrayList<>();
        private final List<Row> growthConsumed = new ArrayList<>();
        private final List<Row> growthProduced = new ArrayList<>();
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
            return addTransition(name, Row.ofDense(consumedPerFiring), Row.ofDense(producedPerFiring),
                circuitSafeTransition, growthSafeTransition);
        }

        /**
         * Adds a transition from sparse per-firing vectors ({@code key -> amount}; absent keys are zero) and returns
         * its index. Storage is proportional to the entries, not to the key count.
         */
        public int addTransition(String name, Map<Integer, PlannerAmount> consumedPerFiring,
                Map<Integer, PlannerAmount> producedPerFiring, boolean circuitSafeTransition,
                boolean growthSafeTransition) {
            return addTransition(name, Row.of(consumedPerFiring, keyCount), Row.of(producedPerFiring, keyCount),
                circuitSafeTransition, growthSafeTransition);
        }

        private int addTransition(String name, Row consumedRow, Row producedRow, boolean circuitSafeTransition,
                boolean growthSafeTransition) {
            int index = transitionNames.size();
            transitionNames.add(name == null ? "t" + index : name);
            consumed.add(consumedRow);
            produced.add(producedRow);
            circuitSafe.add(circuitSafeTransition);
            growthSafe.add(growthSafeTransition);
            rejection.add(null);
            growthRemainder.add(Row.EMPTY);
            // The growth profile defaults to the transition's own vectors; the immutable rows are shared.
            growthConsumed.add(consumedRow);
            growthProduced.add(producedRow);
            return index;
        }

        public Builder growthRemainder(int transition, long[] remainderPerFiring) {
            return growthRemainder(transition, amounts(remainderPerFiring));
        }

        public Builder growthRemainder(int transition, PlannerAmount[] remainderPerFiring) {
            if (remainderPerFiring.length != keyCount) throw new IllegalArgumentException("One remainder entry per key");
            growthRemainder.set(transition, Row.ofDense(remainderPerFiring));
            return this;
        }

        /** Sparse input remainders the growth profile counts ({@code key -> amount}). */
        public Builder growthRemainder(int transition, Map<Integer, PlannerAmount> remainderPerFiring) {
            growthRemainder.set(transition, Row.of(remainderPerFiring, keyCount));
            return this;
        }

        /** Consumption and gross production of the view NeoECO's growth calculator reads (its first declared view). */
        public Builder growthProfile(int transition, PlannerAmount[] consumedPerFiring, PlannerAmount[] grossProducedPerFiring) {
            if (consumedPerFiring.length != keyCount || grossProducedPerFiring.length != keyCount) {
                throw new IllegalArgumentException("Growth profile vectors must have one entry per key");
            }
            growthConsumed.set(transition, Row.ofDense(consumedPerFiring));
            growthProduced.set(transition, Row.ofDense(grossProducedPerFiring));
            return this;
        }

        /** Sparse form of {@link #growthProfile(int, PlannerAmount[], PlannerAmount[])}. */
        public Builder growthProfile(int transition, Map<Integer, PlannerAmount> consumedPerFiring,
                Map<Integer, PlannerAmount> grossProducedPerFiring) {
            growthConsumed.set(transition, Row.of(consumedPerFiring, keyCount));
            growthProduced.set(transition, Row.of(grossProducedPerFiring, keyCount));
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

    /** Immutable sparse vector: keys ascending, non-zero amounts only. Absent keys read as zero. */
    private static final class Row {
        static final Row EMPTY = new Row(new int[0], new PlannerAmount[0]);

        private final int[] keys;
        private final PlannerAmount[] values;

        private Row(int[] keys, PlannerAmount[] values) {
            this.keys = keys;
            this.values = values;
        }

        static Row ofDense(PlannerAmount[] dense) {
            int count = 0;
            for (PlannerAmount value : dense) if (Objects.requireNonNull(value, "amount").signum() != 0) count++;
            if (count == 0) return EMPTY;
            int[] keys = new int[count];
            PlannerAmount[] values = new PlannerAmount[count];
            int next = 0;
            for (int key = 0; key < dense.length; key++) {
                if (dense[key].signum() == 0) continue;
                keys[next] = key;
                values[next++] = dense[key];
            }
            return new Row(keys, values);
        }

        static Row of(Map<Integer, PlannerAmount> entries, int keyCount) {
            TreeMap<Integer, PlannerAmount> sorted = new TreeMap<>();
            for (Map.Entry<Integer, PlannerAmount> entry : entries.entrySet()) {
                int key = Objects.requireNonNull(entry.getKey(), "key");
                if (key < 0 || key >= keyCount) throw new IndexOutOfBoundsException("key " + key);
                PlannerAmount value = Objects.requireNonNull(entry.getValue(), "amount");
                if (value.signum() != 0) sorted.put(key, value);
            }
            if (sorted.isEmpty()) return EMPTY;
            int[] keys = new int[sorted.size()];
            PlannerAmount[] values = new PlannerAmount[sorted.size()];
            int next = 0;
            for (Map.Entry<Integer, PlannerAmount> entry : sorted.entrySet()) {
                keys[next] = entry.getKey();
                values[next++] = entry.getValue();
            }
            return new Row(keys, values);
        }

        PlannerAmount get(int key) {
            int index = Arrays.binarySearch(keys, key);
            return index >= 0 ? values[index] : PlannerAmount.ZERO;
        }

        void copyInto(PlannerAmount[] row) {
            for (int i = 0; i < keys.length; i++) row[keys[i]] = values[i];
        }
    }
}
