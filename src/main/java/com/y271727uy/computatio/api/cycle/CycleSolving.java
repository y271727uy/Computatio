package com.y271727uy.computatio.api.cycle;

import com.y271727uy.computatio.algorithm.amount.PlannerAmount;
import com.y271727uy.computatio.algorithm.cycle.CycleStateEquation;
import com.y271727uy.computatio.algorithm.cycle.PlanningBudget;
import com.y271727uy.computatio.algorithm.cycle.RepeatLayout;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Exact material balance and compact-plan arithmetic. Cyclic components themselves are solved through
 * {@link CycleProblem}.
 */
public final class CycleSolving {
    private CycleSolving() {
    }

    /**
     * NeoECO's {@code CycleStateEquation.solveSparseAmounts}: exact integer solution of
     * {@code stock - sum(column * x) >= target}, {@code x >= 0}. {@code columns.get(j)} maps a key index to the net
     * consumption of one firing of column {@code j}; keys flagged in {@code imports} are unconstrained;
     * {@code lowerBounds} restricts selected firing counts; {@code firstFeasible} stops at the first integer vector
     * instead of proving the minimum. The amounts are converted before solving, so the checkpoints spent are exactly
     * those of NeoECO's call. Pass a {@link CycleBudget} to share an allowance.
     */
    public static MaterialBalance balance(List<Map<Integer, BigInteger>> columns, boolean[] imports, BigInteger[] stock,
            BigInteger[] target, Map<Integer, BigInteger> lowerBounds, boolean firstFeasible, CycleCheck check)
            throws InterruptedException {
        List<Map<Integer, PlannerAmount>> amounts = new ArrayList<>(columns.size());
        for (Map<Integer, BigInteger> column : columns) amounts.add(Bridge.amounts(column));
        Map<Integer, PlannerAmount> bounds = lowerBounds == null ? Map.of() : Bridge.amounts(lowerBounds);
        try {
            return toBalance(CycleStateEquation.solveSparseAmounts(amounts, imports, Bridge.amounts(stock),
                Bridge.amounts(target), bounds, firstFeasible, Bridge.cancellation(check)));
        } catch (PlanningBudget.Exhausted exhausted) {
            throw Bridge.exhausted(exhausted);
        }
    }

    /**
     * NeoECO's {@code CycleStateEquation.solveMaterialBalance}: minimum-firing balance over dense per-firing
     * consumption and production matrices ({@code [column][key]}), with explicit imports.
     */
    public static MaterialBalance balance(long[][] consumed, long[][] produced, boolean[] imports, BigInteger[] stock,
            BigInteger[] target, CycleCheck check) throws InterruptedException {
        try {
            return toBalance(CycleStateEquation.solveMaterialBalance(consumed, produced, imports, Bridge.amounts(stock),
                Bridge.amounts(target), Bridge.cancellation(check)));
        } catch (PlanningBudget.Exhausted exhausted) {
            throw Bridge.exhausted(exhausted);
        }
    }

    /** Exact lap multiplier of every entry of a flat, non-nested compact plan; rejects overlapping circuits. */
    public static BigInteger[] lapMultipliers(List<? extends RepeatRun> runs) {
        List<RepeatLayout.Run> converted = new ArrayList<>(runs.size());
        for (RepeatRun run : runs) {
            PlannerAmount repetitions = Bridge.amount(run.repetitions());
            int width = run.repeatWidth();
            converted.add(new RepeatLayout.Run() {
                @Override public int repeatWidth() { return width; }
                @Override public long repetitions() { return repetitions.longValueSaturated(); }
                @Override public PlannerAmount plannerRepetitions() { return repetitions; }
            });
        }
        return Bridge.bigIntegers(RepeatLayout.plannerMultipliers(converted));
    }

    private static MaterialBalance toBalance(CycleStateEquation.Result result) {
        return new MaterialBalance(MaterialBalance.Status.valueOf(result.status().name()),
            Bridge.bigIntegers(result.counts()));
    }
}
