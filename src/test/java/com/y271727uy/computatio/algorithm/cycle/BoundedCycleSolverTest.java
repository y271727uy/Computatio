package com.y271727uy.computatio.algorithm.cycle;

import static org.junit.jupiter.api.Assertions.*;

import com.y271727uy.computatio.algorithm.amount.PlannerAmount;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BoundedCycleSolverTest {
    /** Any fallback to bounded search fails this budget; exact solving is independent of it. */
    private static final CycleSolveLimits EXACT_ONLY = new CycleSolveLimits(8, 16, 1, 1, 0);

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 5})
    void wideRingTargetsKeepAnExactSolutionWithoutBoundedSearch(int size) throws Exception {
        for (BigInteger amount : List.of(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE),
                BigInteger.TEN.pow(50))) {
            CycleNet net = ring(size, amount, 1);
            CycleSolution result = new BoundedCycleSolver().solve(net, EXACT_ONLY, Cancellation.NONE);
            assertEquals(CycleSolveStatus.SUCCESS, result.status(), result.summary());
            assertTrue(result.hasExactExecutionCounts());
            BigInteger expected = amount.subtract(BigInteger.ONE);
            for (int t = 0; t < size; t++) assertEquals(expected, result.firingsOf(t).toBigInteger());
            assertTrue(result.seedShortfall().stream().allMatch(value -> value.signum() == 0));
            assertTrue(has(result, CycleSolveDiagnostic.Code.STATE_EQUATION_WITNESS));
            assertFalse(has(result, CycleSolveDiagnostic.Code.STATE_BUDGET_EXHAUSTED));
            assertBalance(net, result);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void wideTargetStillReportsMissingStartupSeed(int size) throws Exception {
        CycleSolution result = new BoundedCycleSolver().solve(ring(size, BigInteger.TEN.pow(20), 0), EXACT_ONLY,
            Cancellation.NONE);
        assertEquals(CycleSolveStatus.INSUFFICIENT_EXTERNAL_INPUT, result.status(), result.summary());
        assertTrue(result.hasExactExecutionCounts());
        assertTrue(result.seedShortfall().stream().anyMatch(value -> value.signum() > 0),
            "Exact arithmetic must not create a free startup seed");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void smallRingPlanReplaysWithoutNegativeStock(int size) throws Exception {
        CycleNet net = ring(size, BigInteger.valueOf(32), 1);
        CycleSolution result = new BoundedCycleSolver().solve(net, EXACT_ONLY, Cancellation.NONE);
        assertEquals(CycleSolveStatus.SUCCESS, result.status(), result.summary());
        int[] steps = result.expandedPlan(10_000);
        assertNotNull(steps);
        long[] stock = new long[size];
        stock[0] = 1;
        long[] fired = new long[size];
        for (int t : steps) {
            fired[t]++;
            for (int k = 0; k < size; k++) {
                stock[k] -= net.consumed(t, k);
                assertTrue(stock[k] >= 0, "negative stock at key " + k);
                stock[k] += net.produced(t, k);
            }
        }
        assertTrue(stock[0] >= 32);
        for (int t = 0; t < size; t++) assertEquals(result.firingsOf(t).longValueExact(), fired[t]);
    }

    @Test
    void singleRecipeGrowthUsesClosedFormAndOneUnitSeed() throws Exception {
        CycleNet net = doubling(1, 100);
        CycleSolution result = new BoundedCycleSolver().solve(net, EXACT_ONLY, Cancellation.NONE);
        assertEquals(CycleSolveStatus.SUCCESS, result.status(), result.summary());
        assertTrue(has(result, CycleSolveDiagnostic.Code.SINGLE_PATTERN_NET_GROWTH));
        assertEquals(PlannerAmount.of(99), result.firingsOf(0));
        assertEquals(PlannerAmount.ONE, result.requiredSeed().get(0));
    }

    @Test
    void growthWithoutSeedReportsExactShortfall() throws Exception {
        CycleNet net = doubling(0, 100);
        CycleSolution result = new BoundedCycleSolver().solve(net, EXACT_ONLY, Cancellation.NONE);
        assertEquals(CycleSolveStatus.INSUFFICIENT_EXTERNAL_INPUT, result.status(), result.summary());
        assertEquals(PlannerAmount.ONE, result.seedShortfall().get(0));
    }

    @Test
    void containerLoopNeedsOneContainerAndImportsBoundaryMaterials() throws Exception {
        // keys: 0 bucket, 1 water bucket, 2 water (boundary), 3 sand (boundary), 4 concrete (target)
        CycleNet.Builder builder = CycleNet.builder(5).member(0).member(1).boundary(2).boundary(3)
            .stock(0, 1).required(4, 64);
        builder.addTransition("fill", new long[] {1, 0, 1, 0, 0}, new long[] {0, 1, 0, 0, 0});
        builder.addTransition("mix", new long[] {0, 1, 0, 1, 0}, new long[] {1, 0, 0, 0, 1});
        CycleSolution result = new BoundedCycleSolver().solve(builder.build(), CycleSolveLimits.DEFAULT,
            Cancellation.NONE);
        assertEquals(CycleSolveStatus.SUCCESS, result.status(), result.summary());
        assertEquals(PlannerAmount.of(64), result.firingsOf(0));
        assertEquals(PlannerAmount.of(64), result.firingsOf(1));
        assertEquals(PlannerAmount.of(64), result.externalDemand().get(2));
        assertEquals(PlannerAmount.of(64), result.externalDemand().get(3));
        assertEquals(PlannerAmount.ONE, result.requiredSeed().get(0));
    }

    @Test
    void nonCircuitSafeTransitionsFallBackToVerifiedSearch() throws Exception {
        CycleNet.Builder builder = CycleNet.builder(2).member(0).member(1).stock(0, 1).required(0, 4);
        builder.addTransition("a-to-b", new long[] {1, 0}, new long[] {0, 1}, false, false);
        builder.addTransition("b-to-2a", new long[] {0, 1}, new long[] {2, 0}, false, false);
        CycleSolution result = new BoundedCycleSolver().solve(builder.build(), CycleSolveLimits.DEFAULT,
            Cancellation.NONE);
        assertEquals(CycleSolveStatus.SUCCESS, result.status(), result.summary());
        assertTrue(has(result, CycleSolveDiagnostic.Code.STATIC_CYCLE_CONTRACT_UNAVAILABLE));
        assertTrue(has(result, CycleSolveDiagnostic.Code.WITNESS_FOUND));
    }

    @Test
    void conservativeLoopCannotCreateMaterial() throws Exception {
        CycleNet.Builder builder = CycleNet.builder(2).member(0).member(1).stock(0, 1).required(0, 5);
        builder.addTransition("a-to-b", new long[] {1, 0}, new long[] {0, 1});
        builder.addTransition("b-to-a", new long[] {0, 1}, new long[] {1, 0});
        CycleSolution result = new BoundedCycleSolver().solve(builder.build(), CycleSolveLimits.DEFAULT,
            Cancellation.NONE);
        assertEquals(CycleSolveStatus.INSUFFICIENT_EXTERNAL_INPUT, result.status(), result.summary());
        assertFalse(result.status().solved());
    }

    @Test
    void stockAlreadyCoveringTheTargetNeedsNoFiring() throws Exception {
        CycleNet net = doubling(10, 5);
        CycleSolution result = new BoundedCycleSolver().solve(net, CycleSolveLimits.DEFAULT, Cancellation.NONE);
        assertEquals(CycleSolveStatus.SUCCESS, result.status());
        assertTrue(has(result, CycleSolveDiagnostic.Code.SATISFIED_FROM_STOCK));
        assertEquals(PlannerAmount.ZERO, result.totalFirings());
    }

    @Test
    void verifyFiringsCertifiesOnlyTheExactCandidate() throws Exception {
        CycleNet net = ring(2, BigInteger.valueOf(10), 1);
        BoundedCycleSolver solver = new BoundedCycleSolver();
        CycleSolution exact = solver.verifyFirings(net, new PlannerAmount[] {PlannerAmount.of(9), PlannerAmount.of(9)},
            CycleSolveLimits.DEFAULT, Cancellation.NONE);
        assertNotNull(exact);
        assertEquals(CycleSolveStatus.SUCCESS, exact.status(), exact.summary());
        assertNull(solver.verifyFirings(net, new PlannerAmount[] {PlannerAmount.of(-1), PlannerAmount.ONE},
            CycleSolveLimits.DEFAULT, Cancellation.NONE));
    }

    @Test
    void cancellationAndSharedBudgetPropagate() {
        CycleNet.Builder builder = CycleNet.builder(2).member(0).member(1).stock(0, 1).required(0, 1000);
        builder.addTransition("a-to-b", new long[] {1, 0}, new long[] {0, 1}, false, false);
        builder.addTransition("b-to-2a", new long[] {0, 1}, new long[] {2, 0}, false, false);
        CycleNet net = builder.build();
        assertThrows(InterruptedException.class, () -> new BoundedCycleSolver().solve(net, CycleSolveLimits.DEFAULT,
            () -> { throw new InterruptedException(); }));
        assertEquals(CycleSolveStatus.CANCELLED, new BoundedCycleSolver().solveOrCancelled(net,
            CycleSolveLimits.DEFAULT, () -> { throw new InterruptedException(); }).status());
        assertTrue(Thread.interrupted(), "solveOrCancelled restores the interrupt flag");
        assertThrows(PlanningBudget.Exhausted.class, () -> new BoundedCycleSolver().solve(net,
            CycleSolveLimits.DEFAULT, new PlanningBudget(Cancellation.NONE, 3, Long.MAX_VALUE, () -> 0L)));
    }

    @Test
    void transitionWithoutOutputIsRejected() throws Exception {
        CycleNet.Builder builder = CycleNet.builder(1).required(0, 1);
        builder.addTransition("sink", new long[] {1}, new long[] {0});
        CycleSolution result = new BoundedCycleSolver().solve(builder.build(), CycleSolveLimits.DEFAULT,
            Cancellation.NONE);
        assertEquals(CycleSolveStatus.UNSUPPORTED_PATTERN, result.status());
    }

    private static CycleNet doubling(long stock, long required) {
        CycleNet.Builder builder = CycleNet.builder(1).keyName(0, "seed").member(0).stock(0, stock)
            .required(0, required);
        builder.addTransition("double", new long[] {1}, new long[] {2});
        return builder.build();
    }

    /** Key i is consumed by transition i, which produces key i+1; the last transition produces two of key 0. */
    private static CycleNet ring(int size, BigInteger target, long stock) {
        CycleNet.Builder builder = CycleNet.builder(size);
        for (int k = 0; k < size; k++) builder.member(k);
        builder.stock(0, stock).required(0, PlannerAmount.of(target));
        for (int t = 0; t < size; t++) {
            long[] in = new long[size];
            long[] out = new long[size];
            in[t] = 1;
            out[(t + 1) % size] += t == size - 1 ? 2 : 1;
            // Growth-unsafe so even the one-transition ring exercises the state-equation witness.
            builder.addTransition("ring" + t, in, out, true, false);
        }
        return builder.build();
    }

    private static void assertBalance(CycleNet net, CycleSolution result) {
        for (int k = 0; k < net.keyCount(); k++) {
            BigInteger balance = net.stock(k).toBigInteger();
            for (int t = 0; t < net.transitionCount(); t++) {
                balance = balance.add(result.firingsOf(t).toBigInteger()
                    .multiply(BigInteger.valueOf(net.produced(t, k) - net.consumed(t, k))));
            }
            assertTrue(balance.compareTo(net.required(k).toBigInteger()) >= 0, "key " + k);
        }
    }

    private static boolean has(CycleSolution result, CycleSolveDiagnostic.Code code) {
        return result.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code() == code);
    }
}
