package com.y271727uy.computatio.algorithm.cycle;

/** Plug-in extension point for solving one cyclic component described by a {@link CycleNet}. */
public interface CycleSolver {
    CycleSolution solve(CycleNet net, CycleSolveLimits limits, Cancellation cancellation) throws InterruptedException;

    /**
     * Value-returning variant for callers that prefer a {@link CycleSolveStatus#CANCELLED} result over an
     * exception. The interrupt flag is restored so outer loops still observe the cancellation.
     */
    default CycleSolution solveOrCancelled(CycleNet net, CycleSolveLimits limits, Cancellation cancellation) {
        try {
            return solve(net, limits, cancellation);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CycleSolution.cancelled();
        }
    }
}
