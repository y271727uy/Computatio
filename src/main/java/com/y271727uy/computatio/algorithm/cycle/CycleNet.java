package com.y271727uy.computatio.algorithm.cycle;

import com.y271727uy.computatio.algorithm.amount.PlannerAmount;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Immutable, platform-independent description of one cyclic component, read as a small Petri net.
 *
 * <p>A <em>key</em> (place) is one material: a component member, a transition input or output, or a required
 * output. A <em>transition</em> is one distinct recipe. Callers must deduplicate several views of the same
 * physical recipe before building the net, or a single firing would be counted twice.
 *
 * <ul>
 *   <li>{@code consumed[t][k]} / {@code produced[t][k]}: gross per-firing amounts. A catalyst or returned
 *   container is consumed and produced by the same firing; it is never pre-netted.</li>
 *   <li>{@code boundary[k]}: the rest of the caller's dependency graph can supply this key, so a deficit on it is
 *   imported and booked as external demand. Every other key is owned by the component and a deficit on it is a
 *   start-up seed that only stock can cover.</li>
 *   <li>{@code member[k]}: the key belongs to the cyclic component itself.</li>
 *   <li>{@code circuitSafe[t]}: the recipe's static contract allows exact circuit algebra (state equation and
 *   repeated-lap acceleration). When any transition is not circuit-safe the solver falls back to bounded search.</li>
 *   <li>{@code growthSafe[t]}: the recipe may use the closed-form single-recipe net-growth calculation.</li>
 *   <li>{@code growthRemainder[t][k]}: determinate per-firing returns recorded on the recipe's inputs (for example an
 *   empty container handed back). They are <em>not</em> part of {@code produced}; only the single-recipe growth
 *   calculation counts them, exactly as NeoECO's validated pattern profile does. Zero unless set.</li>
 *   <li>{@code declaredTransitionCount}: how many recipe views the caller saw before deduplication (at least the
 *   transition count). It only sets how much shared planning budget preparation consumes, matching NeoECO.</li>
 *   <li>{@code rejection[t]} / {@code unrepresentableTotals[t]}: caller-detected reasons a recipe cannot be modelled
 *   (NeoECO's {@code unsupportedReason}) and a per-recipe input/output total beyond the long range. The solver
 *   reports them at the same point of preparation, with the same budget use, as NeoECO. Null unless set.</li>
 *   <li>{@code stock[k]} / {@code required[k]}: the inventory snapshot and the absolute on-hand target.</li>
 * </ul>
 */
public final class CycleNet {
    private final int keyCount;
    private final List<String> keyNames;
    private final List<String> transitionNames;
    private final long[][] consumed;
    private final long[][] produced;
    private final boolean[] boundary;
    private final boolean[] member;
    private final boolean[] circuitSafe;
    private final boolean[] growthSafe;
    private final long[][] growthRemainder;
    private final int declaredTransitionCount;
    private final String[] rejection;
    private final String[] unrepresentableTotals;
    private final PlannerAmount[] stock;
    private final PlannerAmount[] required;

    private CycleNet(Builder builder) {
        this.keyCount = builder.keyCount;
        this.keyNames = List.copyOf(Arrays.asList(builder.keyNames));
        this.transitionNames = List.copyOf(builder.transitionNames);
        this.consumed = builder.consumed.stream().map(long[]::clone).toArray(long[][]::new);
        this.produced = builder.produced.stream().map(long[]::clone).toArray(long[][]::new);
        this.boundary = builder.boundary.clone();
        this.member = builder.member.clone();
        this.circuitSafe = toArray(builder.circuitSafe);
        this.growthSafe = toArray(builder.growthSafe);
        this.growthRemainder = builder.growthRemainder.stream().map(long[]::clone).toArray(long[][]::new);
        this.declaredTransitionCount = Math.max(builder.declaredTransitionCount, builder.transitionNames.size());
        this.rejection = builder.rejection.toArray(new String[0]);
        this.unrepresentableTotals = builder.unrepresentableTotals.toArray(new String[0]);
        this.stock = builder.stock.clone();
        this.required = builder.required.clone();
    }

    public static Builder builder(int keyCount) {
        return new Builder(keyCount);
    }

    public int keyCount() { return keyCount; }
    public int transitionCount() { return transitionNames.size(); }
    public String keyName(int key) { return keyNames.get(key); }
    public String transitionName(int transition) { return transitionNames.get(transition); }
    public long consumed(int transition, int key) { return consumed[transition][key]; }
    public long produced(int transition, int key) { return produced[transition][key]; }
    public boolean boundary(int key) { return boundary[key]; }
    public boolean member(int key) { return member[key]; }
    public boolean circuitSafe(int transition) { return circuitSafe[transition]; }
    public boolean growthSafe(int transition) { return growthSafe[transition]; }
    public long growthRemainder(int transition, int key) { return growthRemainder[transition][key]; }
    public int declaredTransitionCount() { return declaredTransitionCount; }
    /** Caller-detected reason the recipe cannot be modelled, or null. */
    public String rejection(int transition) { return rejection[transition]; }
    /** Caller-detected per-recipe total beyond the long range (message detail), or null. */
    public String unrepresentableTotals(int transition) { return unrepresentableTotals[transition]; }
    public PlannerAmount stock(int key) { return stock[key]; }
    public PlannerAmount required(int key) { return required[key]; }

    long[][] consumedMatrix() { return consumed; }
    long[][] producedMatrix() { return produced; }
    boolean[] boundaryArray() { return boundary; }
    boolean[] memberArray() { return member; }
    PlannerAmount[] stockArray() { return stock; }
    PlannerAmount[] requiredArray() { return required; }

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
            builder.keyName(key, keyNames.get(key)).stock(key, stock[key]).required(key, required[key]);
            if (boundary[key]) builder.boundary(key);
            if (member[key]) builder.member(key);
        }
        for (int t = 0; t < transitionCount(); t++) {
            builder.addTransition(transitionNames.get(t), consumed[t], produced[t], circuitSafe[t], growthSafe[t]);
            builder.growthRemainder(t, growthRemainder[t]);
            if (rejection[t] != null) builder.rejectTransition(t, rejection[t]);
            if (unrepresentableTotals[t] != null) builder.unrepresentableTotals(t, unrepresentableTotals[t]);
        }
        builder.declaredTransitions(declaredTransitionCount);
        return builder;
    }

    private static boolean[] toArray(List<Boolean> values) {
        boolean[] result = new boolean[values.size()];
        for (int i = 0; i < result.length; i++) result[i] = values.get(i);
        return result;
    }

    public static final class Builder {
        private final int keyCount;
        private final String[] keyNames;
        private final boolean[] boundary;
        private final boolean[] member;
        private final PlannerAmount[] stock;
        private final PlannerAmount[] required;
        private final List<String> transitionNames = new ArrayList<>();
        private final List<long[]> consumed = new ArrayList<>();
        private final List<long[]> produced = new ArrayList<>();
        private final List<Boolean> circuitSafe = new ArrayList<>();
        private final List<Boolean> growthSafe = new ArrayList<>();
        private final List<long[]> growthRemainder = new ArrayList<>();
        private int declaredTransitionCount;
        private final List<String> rejection = new ArrayList<>();
        private final List<String> unrepresentableTotals = new ArrayList<>();

        private Builder(int keyCount) {
            if (keyCount < 0) throw new IllegalArgumentException("Key count must not be negative");
            this.keyCount = keyCount;
            this.keyNames = new String[keyCount];
            for (int key = 0; key < keyCount; key++) keyNames[key] = "#" + key;
            this.boundary = new boolean[keyCount];
            this.member = new boolean[keyCount];
            this.stock = new PlannerAmount[keyCount];
            this.required = new PlannerAmount[keyCount];
            Arrays.fill(stock, PlannerAmount.ZERO);
            Arrays.fill(required, PlannerAmount.ZERO);
        }

        public Builder keyName(int key, String name) {
            keyNames[checkKey(key)] = name == null ? "#" + key : name;
            return this;
        }

        public Builder boundary(int key) {
            boundary[checkKey(key)] = true;
            return this;
        }

        public Builder member(int key) {
            member[checkKey(key)] = true;
            return this;
        }

        public Builder stock(int key, long amount) {
            return stock(key, PlannerAmount.of(amount));
        }

        public Builder stock(int key, PlannerAmount amount) {
            stock[checkKey(key)] = nonNegative(amount, "stock");
            return this;
        }

        public Builder required(int key, long amount) {
            return required(key, PlannerAmount.of(amount));
        }

        public Builder required(int key, PlannerAmount amount) {
            required[checkKey(key)] = nonNegative(amount, "required");
            return this;
        }

        /** Adds a circuit-safe and growth-safe transition and returns its index. */
        public int addTransition(String name, long[] consumedPerFiring, long[] producedPerFiring) {
            return addTransition(name, consumedPerFiring, producedPerFiring, true, true);
        }

        /** Adds a transition and returns its index. Arrays are copied and indexed by key. */
        public int addTransition(String name, long[] consumedPerFiring, long[] producedPerFiring,
                boolean circuitSafeTransition, boolean growthSafeTransition) {
            if (consumedPerFiring.length != keyCount || producedPerFiring.length != keyCount) {
                throw new IllegalArgumentException("Transition vectors must have one entry per key");
            }
            for (int key = 0; key < keyCount; key++) {
                if (consumedPerFiring[key] < 0 || producedPerFiring[key] < 0) {
                    throw new IllegalArgumentException("Per-firing amounts must not be negative");
                }
            }
            int index = transitionNames.size();
            transitionNames.add(name == null ? "t" + index : name);
            consumed.add(consumedPerFiring.clone());
            produced.add(producedPerFiring.clone());
            circuitSafe.add(circuitSafeTransition);
            growthSafe.add(growthSafeTransition);
            growthRemainder.add(new long[keyCount]);
            rejection.add(null);
            unrepresentableTotals.add(null);
            return index;
        }

        /**
         * Sets the per-firing returns recorded on a transition's inputs, indexed by key (see the class notes). Only
         * the single-recipe growth calculation reads them.
         */
        public Builder growthRemainder(int transition, long[] remainderPerFiring) {
            if (remainderPerFiring.length != keyCount) {
                throw new IllegalArgumentException("Remainder vector must have one entry per key");
            }
            for (long amount : remainderPerFiring) {
                if (amount < 0) throw new IllegalArgumentException("Remainder amounts must not be negative");
            }
            growthRemainder.set(transition, remainderPerFiring.clone());
            return this;
        }

        /**
         * Marks a recipe the caller cannot model (NeoECO's {@code unsupportedReason}). A reason starting with
         * {@code "AMOUNT_UNREPRESENTABLE:"} is reported as {@link CycleSolveStatus#UNREPRESENTABLE}, any other as
         * {@link CycleSolveStatus#UNSUPPORTED_PATTERN}; the first marked transition in order wins.
         */
        public Builder rejectTransition(int transition, String reason) {
            rejection.set(transition, java.util.Objects.requireNonNull(reason, "reason"));
            return this;
        }

        /**
         * Marks a recipe whose summed input or output amount for one key exceeds the long range, so its row cannot
         * be built; {@code detail} completes the diagnostic (for example {@code "input total for k amount=… max=…"}).
         */
        public Builder unrepresentableTotals(int transition, String detail) {
            unrepresentableTotals.set(transition, java.util.Objects.requireNonNull(detail, "detail"));
            return this;
        }

        /** Number of recipe views before deduplication; values below the transition count are raised to it. */
        public Builder declaredTransitions(int count) {
            if (count < 0) throw new IllegalArgumentException("Declared transition count must not be negative");
            declaredTransitionCount = count;
            return this;
        }

        public CycleNet build() {
            return new CycleNet(this);
        }

        private int checkKey(int key) {
            if (key < 0 || key >= keyCount) throw new IndexOutOfBoundsException("key " + key);
            return key;
        }

        private static PlannerAmount nonNegative(PlannerAmount amount, String what) {
            if (amount == null || amount.signum() < 0) throw new IllegalArgumentException(what + " must not be negative");
            return amount;
        }
    }
}
