package com.y271727uy.computatio.api.cycle;

import com.y271727uy.computatio.algorithm.amount.PlannerAmount;
import com.y271727uy.computatio.algorithm.cycle.BoundedCycleSolver;
import com.y271727uy.computatio.algorithm.cycle.Cancellation;
import com.y271727uy.computatio.algorithm.cycle.CycleNet;
import com.y271727uy.computatio.algorithm.cycle.CycleSolution;
import com.y271727uy.computatio.algorithm.cycle.CycleSolveLimits;
import com.y271727uy.computatio.algorithm.cycle.CycleSolver;
import com.y271727uy.computatio.algorithm.cycle.CycleStateEquation;
import com.y271727uy.computatio.algorithm.cycle.PlanningBudget;
import com.y271727uy.computatio.algorithm.cycle.RepeatLayout;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/**
 * Solves cyclic recipe components described by a {@link CycleNet}.
 *
 * <p>Build the net from frozen data on the platform thread, then solve on any thread. Inspect
 * {@link CycleSolution#status()}: only {@code SUCCESS} carries an executable plan whose seed is covered by stock,
 * {@code INSUFFICIENT_EXTERNAL_INPUT} is a proof at the given stock, and {@code UNKNOWN_BUDGET} proves nothing.
 */
public final class CycleSolving {
    private static final CycleSolver DEFAULT_SOLVER = new BoundedCycleSolver();

    private CycleSolving() {
    }

    /** The shared default solver (bounded marking search with exact circuit algebra). */
    public static CycleSolver defaultSolver() {
        return DEFAULT_SOLVER;
    }

    /** Solves on the calling thread with default limits and the default {@link PlanningBudget}. */
    public static CycleSolution solve(CycleNet net) throws InterruptedException {
        return solve(net, CycleSolveLimits.DEFAULT, new PlanningBudget(Cancellation.NONE));
    }

    /**
     * Solves on the calling thread. {@link PlanningBudget.Exhausted} and {@link InterruptedException} propagate;
     * pass a {@link PlanningBudget} as {@code cancellation} to bound work and wall time.
     */
    public static CycleSolution solve(CycleNet net, CycleSolveLimits limits, Cancellation cancellation)
            throws InterruptedException {
        Objects.requireNonNull(net, "net");
        Objects.requireNonNull(cancellation, "cancellation");
        return DEFAULT_SOLVER.solve(net, limits == null ? CycleSolveLimits.DEFAULT : limits, cancellation);
    }

    /**
     * Solves on the supplied executor with default limits and the default {@link PlanningBudget}. Cancelling the
     * returned future stops the search at its next checkpoint.
     */
    public static CompletableFuture<CycleSolution> solveAsync(CycleNet net, Executor workerExecutor) {
        return solveAsync(net, CycleSolveLimits.DEFAULT, workerExecutor);
    }

    /** Asynchronous variant of {@link #solve(CycleNet, CycleSolveLimits, Cancellation)} with the default budget. */
    public static CompletableFuture<CycleSolution> solveAsync(CycleNet net, CycleSolveLimits limits,
            Executor workerExecutor) {
        Objects.requireNonNull(net, "net");
        Objects.requireNonNull(workerExecutor, "workerExecutor");
        CompletableFuture<CycleSolution> future = new CompletableFuture<>();
        workerExecutor.execute(() -> {
            if (future.isDone()) return;
            Cancellation onCancel = () -> {
                if (future.isCancelled()) throw new InterruptedException("Cycle solve was cancelled");
            };
            try {
                future.complete(solve(net, limits, new PlanningBudget(onCancel)));
            } catch (InterruptedException cancelled) {
                future.complete(CycleSolution.cancelled());
            } catch (Throwable failure) {
                future.completeExceptionally(failure instanceof CompletionException ? failure
                    : new CompletionException(failure));
            }
        });
        return future;
    }

    /**
     * Verifies a fixed integer firing candidate with prefix/repeat algebra, without optimizing it again. Returns
     * {@code null} when the net does not admit circuit algebra, a count is negative, or the certificate would not
     * verify exactly this candidate.
     */
    public static CycleSolution verifyFirings(CycleNet net, PlannerAmount[] counts, Cancellation cancellation)
            throws InterruptedException {
        Objects.requireNonNull(net, "net");
        Objects.requireNonNull(counts, "counts");
        return new BoundedCycleSolver().verifyFirings(net, counts, CycleSolveLimits.DEFAULT,
            cancellation == null ? Cancellation.NONE : cancellation);
    }

    /**
     * Only the single-recipe net-growth path (NeoECO's {@code BoundedCycleSolver.solveGrowth}). Returns {@code null}
     * when the closed form does not apply, so a caller can fall back to {@link #solve(CycleNet, CycleSolveLimits,
     * Cancellation)} with the same budget, as NeoECO's {@code SinglePatternGrowthCycleSolver} does.
     */
    public static CycleSolution solveGrowth(CycleNet net, CycleSolveLimits limits, Cancellation cancellation)
            throws InterruptedException {
        Objects.requireNonNull(net, "net");
        return new BoundedCycleSolver().solveGrowth(net, limits, cancellation == null ? Cancellation.NONE : cancellation);
    }

    /**
     * NeoECO's map form of {@link #verifyFirings(CycleNet, PlannerAmount[], Cancellation)}, keyed by transition index:
     * the result is returned only when its positive firing counts equal {@code counts} exactly, so an explicit zero
     * entry or a key that is not a transition gives {@code null}.
     */
    public static CycleSolution verifyFirings(CycleNet net, Map<Integer, PlannerAmount> counts, Cancellation cancellation)
            throws InterruptedException {
        Objects.requireNonNull(net, "net");
        Objects.requireNonNull(counts, "counts");
        return new BoundedCycleSolver().verifyFirings(net, counts, CycleSolveLimits.DEFAULT,
            cancellation == null ? Cancellation.NONE : cancellation);
    }

    /**
     * Exact minimum-firing integer solution of {@code stock + (produced - consumed) x >= target, x >= 0}.
     * Keys flagged in {@code imports} are unconstrained. A feasible vector is never a liveness proof.
     */
    public static CycleStateEquation.Result materialBalance(long[][] consumed, long[][] produced, boolean[] imports,
            PlannerAmount[] stock, PlannerAmount[] target, Cancellation cancellation) throws InterruptedException {
        return CycleStateEquation.solveMaterialBalance(consumed, produced, imports, stock, target,
            cancellation == null ? Cancellation.NONE : cancellation);
    }

    /**
     * Sparse variant: each column maps a key to its net consumption per firing (consumed minus produced).
     * {@code lowerBounds} restricts firing counts of selected columns; {@code firstFeasible} stops at the first
     * integer vector instead of proving the minimum.
     */
    public static CycleStateEquation.Result sparseMaterialBalance(List<Map<Integer, BigInteger>> columns,
            boolean[] imports, PlannerAmount[] stock, PlannerAmount[] target, Map<Integer, PlannerAmount> lowerBounds,
            boolean firstFeasible, Cancellation cancellation) throws InterruptedException {
        return CycleStateEquation.solveSparseMaterialBalance(columns, imports, stock, target,
            lowerBounds == null ? Map.of() : lowerBounds, firstFeasible,
            cancellation == null ? Cancellation.NONE : cancellation);
    }

    /** Per-run lap multipliers of a flat, non-nested repeat layout; rejects overlapping circuits. */
    public static long[] repeatMultipliers(List<? extends RepeatLayout.Run> runs) {
        return RepeatLayout.multipliers(Objects.requireNonNull(runs, "runs"));
    }
}
