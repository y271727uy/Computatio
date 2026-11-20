package com.y271727uy.computatio.api.cycle;

import com.y271727uy.computatio.algorithm.amount.PlannerAmount;
import com.y271727uy.computatio.algorithm.cycle.Cancellation;
import com.y271727uy.computatio.algorithm.cycle.PlanningBudget;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Conversions between the public api types and the internal algorithm types. Not part of the api. */
final class Bridge {
    private Bridge() {
    }

    /** A {@link CycleBudget} is handed over as its own internal budget, so the allowance stays shared. */
    static Cancellation cancellation(CycleCheck check) {
        if (check == null) return Cancellation.NONE;
        if (check instanceof CycleBudget budget) return budget.internal();
        return new Cancellation() {
            @Override public void checkpoint() throws InterruptedException {
                try {
                    check.checkpoint();
                } catch (CycleBudgetExhaustedException exhausted) {
                    throw internal(exhausted);
                }
            }
            @Override public boolean improveCompletedPlans() { return check.improveCompletedPlans(); }
        };
    }

    static PlannerAmount amount(BigInteger value) {
        return PlannerAmount.of(Objects.requireNonNull(value, "amount"));
    }

    static PlannerAmount[] amounts(BigInteger[] values) {
        PlannerAmount[] result = new PlannerAmount[values.length];
        for (int i = 0; i < values.length; i++) result[i] = amount(values[i]);
        return result;
    }

    static BigInteger[] bigIntegers(PlannerAmount[] values) {
        if (values == null) return null;
        BigInteger[] result = new BigInteger[values.length];
        for (int i = 0; i < values.length; i++) result[i] = values[i].toBigInteger();
        return result;
    }

    static <K> Map<K, PlannerAmount> amounts(Map<K, BigInteger> values) {
        Map<K, PlannerAmount> result = new LinkedHashMap<>();
        values.forEach((key, value) -> result.put(key, amount(value)));
        return result;
    }

    static <K> Map<K, BigInteger> bigIntegers(Map<K, PlannerAmount> values) {
        Map<K, BigInteger> result = new LinkedHashMap<>();
        values.forEach((key, value) -> result.put(key, value.toBigInteger()));
        return result;
    }

    /** A check's reported exhaustion as the solver's own, so its optional stages react to it as to a budget. */
    static PlanningBudget.Exhausted internal(CycleBudgetExhaustedException exhausted) {
        if (exhausted.getCause() instanceof PlanningBudget.Exhausted original) return original;
        return PlanningBudget.Exhausted.of(exhausted.getMessage());
    }

    static CycleBudgetExhaustedException exhausted(PlanningBudget.Exhausted exhausted) {
        return CycleBudgetExhaustedException.of(exhausted);
    }
}
