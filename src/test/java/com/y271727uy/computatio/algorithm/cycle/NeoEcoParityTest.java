package com.y271727uy.computatio.algorithm.cycle;

import static org.junit.jupiter.api.Assertions.*;

import com.y271727uy.computatio.algorithm.amount.PlannerAmount;
import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Regression tests for the differences a static comparison against NeoECO's original solver found. */
class NeoEcoParityTest {

    private static long checkpoints(CycleNet net) throws InterruptedException {
        AtomicLong calls = new AtomicLong();
        new BoundedCycleSolver().solve(net, CycleSolveLimits.DEFAULT,
            new PlanningBudget(calls::incrementAndGet, Long.MAX_VALUE, Long.MAX_VALUE, () -> 0L));
        return calls.get();
    }

    private static CycleNet.Builder doubling() {
        CycleNet.Builder builder = CycleNet.builder(1).member(0).stock(0, 1).required(0, 64);
        builder.addTransition("double", new long[] {1}, new long[] {2});
        return builder;
    }

    /** NeoECO's prepare checkpoints once per declared recipe view while deduplicating, then once per transition twice. */
    @Test
    void preparationConsumesOneCheckpointPerDeclaredView() throws Exception {
        long single = checkpoints(doubling().build());
        long declaredFive = checkpoints(doubling().declaredTransitions(5).build());
        assertEquals(4, declaredFive - single);
    }

    @Test
    void preparationCheckpointsMatchNeoEcoForEveryShape() throws Exception {
        // solve 1 + dedup(declared) + matrices(t) + metadata(t) before any solving stage; the doubling growth path
        // adds no checkpoint of its own, so the whole solve is exactly 1 + 3t here.
        assertEquals(1 + 3, checkpoints(doubling().build()));
    }

    /** A plain cancellation is wrapped in the default shared allowance, as NeoECO's solve does. */
    @Test
    void plainCancellationGetsTheDefaultBudget() throws Exception {
        AtomicLong calls = new AtomicLong();
        CycleSolution result = new BoundedCycleSolver().solve(doubling().build(), CycleSolveLimits.DEFAULT,
            calls::incrementAndGet);
        assertEquals(CycleSolveStatus.SUCCESS, result.status());
        assertEquals(4, calls.get(), "the wrapped budget forwards every checkpoint to the caller");
    }

    /** An input remainder of the consumed key makes it a second feedback key, so NeoECO declines the growth path. */
    @Test
    void inputRemainderCountsAsFeedbackLikeNeoEcoProfiles() throws Exception {
        // keys: 0 seed X (member), 1 bucket. Fire: 1 X + 1 bucket -> 2 X, the bucket handed back as an input remainder.
        CycleNet.Builder builder = CycleNet.builder(2).member(0).stock(0, 1).stock(1, 1).required(0, 64);
        int t = builder.addTransition("grow", new long[] {1, 1}, new long[] {2, 0});
        builder.growthRemainder(t, new long[] {0, 1});
        CycleSolution result = new BoundedCycleSolver().solve(builder.build(), CycleSolveLimits.DEFAULT,
            new PlanningBudget(Cancellation.NONE));
        assertTrue(result.diagnostics().stream().noneMatch(d -> d.code() == CycleSolveDiagnostic.Code.SINGLE_PATTERN_NET_GROWTH),
            result.summary());

        // Without the remainder the same recipe is the textbook single-feedback growth case.
        CycleNet.Builder plain = CycleNet.builder(2).member(0).stock(0, 1).stock(1, 1).required(0, 64);
        plain.addTransition("grow", new long[] {1, 1}, new long[] {2, 0});
        CycleSolution growth = new BoundedCycleSolver().solve(plain.build(), CycleSolveLimits.DEFAULT,
            new PlanningBudget(Cancellation.NONE));
        assertTrue(growth.diagnostics().stream().anyMatch(d -> d.code() == CycleSolveDiagnostic.Code.SINGLE_PATTERN_NET_GROWTH),
            growth.summary());
    }

    /** A remainder of the feedback key itself raises the profile's gross production and hence its firing count. */
    @Test
    void feedbackRemainderEntersTheGrowthFiringCount() throws Exception {
        // 2 X -> 2 X with 1 X handed back as a remainder: the profile sees +1 per firing; the replay model sees 0,
        // so NeoECO's growth witness fails its own replay and the solve continues without the growth diagnostic.
        CycleNet.Builder builder = CycleNet.builder(1).member(0).stock(0, 2).required(0, 10);
        int t = builder.addTransition("loop", new long[] {2}, new long[] {2});
        builder.growthRemainder(t, new long[] {1});
        CycleSolution result = new BoundedCycleSolver().solve(builder.build(), CycleSolveLimits.DEFAULT,
            new PlanningBudget(Cancellation.NONE));
        assertTrue(result.diagnostics().stream().noneMatch(d -> d.code() == CycleSolveDiagnostic.Code.SINGLE_PATTERN_NET_GROWTH),
            result.summary());
    }

    /** NeoECO returns an equation witness at once when it leaves the long range, even with a seed shortfall. */
    @Test
    void wideEquationWitnessWithShortfallStopsLikeNeoEco() throws Exception {
        CycleNet.Builder builder = CycleNet.builder(3).member(0).member(1).member(2)
            .required(0, PlannerAmount.of(BigInteger.TEN.pow(30)));
        builder.addTransition("a", new long[] {1, 0, 0}, new long[] {0, 1, 0}, true, false);
        builder.addTransition("b", new long[] {0, 1, 0}, new long[] {0, 0, 1}, true, false);
        builder.addTransition("c", new long[] {0, 0, 1}, new long[] {2, 0, 0}, true, false);
        CycleSolution result = new BoundedCycleSolver().solve(builder.build(), CycleSolveLimits.DEFAULT,
            new PlanningBudget(Cancellation.NONE));
        assertEquals(CycleSolveStatus.INSUFFICIENT_EXTERNAL_INPUT, result.status(), result.summary());
        assertTrue(result.exceedsLongRange());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.code() == CycleSolveDiagnostic.Code.STATE_EQUATION_WITNESS));
        assertTrue(result.diagnostics().stream().noneMatch(d -> d.code() == CycleSolveDiagnostic.Code.STATE_EQUATION_BUDGET),
            "no fallback search after the wide equation witness");
    }

    /** keys 0, 1 members; t0: 0 -> 1, t1: 1 -> 2x0, t2: an alternative 0 -> 1 recipe that the candidate leaves idle. */
    private static CycleNet ringWithIdleRecipe() {
        CycleNet.Builder builder = CycleNet.builder(2).member(0).member(1).stock(0, 1).required(0, 10);
        builder.addTransition("a", new long[] {1, 0}, new long[] {0, 1});
        builder.addTransition("b", new long[] {0, 1}, new long[] {2, 0});
        builder.addTransition("idle", new long[] {1, 0}, new long[] {0, 1});
        return builder.build();
    }

    private static java.util.Map<Integer, PlannerAmount> counts(long... values) {
        java.util.Map<Integer, PlannerAmount> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) map.put((int) values[i], PlannerAmount.of(values[i + 1]));
        return map;
    }

    /** NeoECO compares its positive firing counts with the caller's map by Map.equals, after replaying the candidate. */
    @Test
    void verifyFiringsMapFollowsNeoEcoMapEquality() throws Exception {
        CycleNet net = ringWithIdleRecipe();
        BoundedCycleSolver solver = new BoundedCycleSolver();
        CycleSolution exact = solver.verifyFirings(net, counts(0, 9, 1, 9), CycleSolveLimits.DEFAULT, Cancellation.NONE);
        assertNotNull(exact);
        assertEquals(CycleSolveStatus.SUCCESS, exact.status(), exact.summary());
        assertNull(solver.verifyFirings(net, counts(0, 9, 1, 9, 2, 0), CycleSolveLimits.DEFAULT, Cancellation.NONE),
            "an explicit zero entry is not in NeoECO's exactPatternTimes, so the maps differ");
        assertNull(solver.verifyFirings(net, counts(0, 9, 1, 9, 7, 3), CycleSolveLimits.DEFAULT, Cancellation.NONE),
            "a key that is not a transition never matches");
        assertNull(solver.verifyFirings(net, counts(0, -1, 1, 9), CycleSolveLimits.DEFAULT, Cancellation.NONE));
    }

    /** The array form means "only positive entries", i.e. NeoECO called with a map without zero entries. */
    @Test
    void verifyFiringsArrayTreatsZeroAsAbsent() throws Exception {
        CycleNet net = ringWithIdleRecipe();
        BoundedCycleSolver solver = new BoundedCycleSolver();
        CycleSolution viaArray = solver.verifyFirings(net,
            new PlannerAmount[] {PlannerAmount.of(9), PlannerAmount.of(9), PlannerAmount.ZERO}, CycleSolveLimits.DEFAULT,
            Cancellation.NONE);
        CycleSolution viaMap = solver.verifyFirings(net, counts(0, 9, 1, 9), CycleSolveLimits.DEFAULT, Cancellation.NONE);
        assertNotNull(viaArray);
        assertEquals(viaMap.firings(), viaArray.firings());
        assertEquals(viaMap.plan(), viaArray.plan());
        assertNull(solver.verifyFirings(net, new PlannerAmount[] {PlannerAmount.of(9), PlannerAmount.of(9)},
            CycleSolveLimits.DEFAULT, Cancellation.NONE), "wrong length");
    }

    @Test
    void verifyFiringsAcceptsTheEmptyCandidateWhenStockAlreadyCovers() throws Exception {
        CycleNet.Builder builder = CycleNet.builder(2).member(0).member(1).stock(0, 20).required(0, 10);
        builder.addTransition("a", new long[] {1, 0}, new long[] {0, 1});
        builder.addTransition("b", new long[] {0, 1}, new long[] {2, 0});
        CycleSolution result = new BoundedCycleSolver().verifyFirings(builder.build(), counts(),
            CycleSolveLimits.DEFAULT, Cancellation.NONE);
        assertNotNull(result);
        assertEquals(PlannerAmount.ZERO, result.totalFirings());
    }

    /** NeoECO's solveGrowth: one checkpoint, the closed form, and null before any preparation when it does not apply. */
    @Test
    void solveGrowthReturnsNullBeforePreparationWhenTheClosedFormDoesNotApply() throws Exception {
        CycleNet ring = ringWithIdleRecipe(); // three transitions: never a single-recipe growth
        AtomicLong calls = new AtomicLong();
        assertNull(new BoundedCycleSolver().solveGrowth(ring, CycleSolveLimits.DEFAULT,
            new PlanningBudget(calls::incrementAndGet, Long.MAX_VALUE, Long.MAX_VALUE, () -> 0L)));
        assertEquals(1, calls.get(), "no preparation checkpoints");
    }

    @Test
    void solveGrowthMatchesTheFullSolveOnTheGrowthPath() throws Exception {
        AtomicLong growthCalls = new AtomicLong();
        CycleSolution growth = new BoundedCycleSolver().solveGrowth(doubling().build(), CycleSolveLimits.DEFAULT,
            new PlanningBudget(growthCalls::incrementAndGet, Long.MAX_VALUE, Long.MAX_VALUE, () -> 0L));
        CycleSolution full = new BoundedCycleSolver().solve(doubling().build(), CycleSolveLimits.DEFAULT,
            new PlanningBudget(Cancellation.NONE));
        assertNotNull(growth);
        assertEquals(full.status(), growth.status());
        assertEquals(full.firings(), growth.firings());
        assertEquals(full.plan(), growth.plan());
        assertEquals(full.requiredSeed(), growth.requiredSeed());
        assertTrue(growth.diagnostics().stream().anyMatch(d -> d.code() == CycleSolveDiagnostic.Code.SINGLE_PATTERN_NET_GROWTH));
        assertEquals(1 + 3, growthCalls.get(), "solveGrowth checkpoint, then dedup + matrices + metadata");
    }

    private static CycleNet.Builder twoStepRing() {
        CycleNet.Builder builder = CycleNet.builder(2).member(0).member(1).stock(0, 1).required(0, 10);
        builder.addTransition("a", new long[] {1, 0}, new long[] {0, 1});
        builder.addTransition("b", new long[] {0, 1}, new long[] {2, 0});
        return builder;
    }

    private static CycleSolution solveCounting(CycleNet net, AtomicLong calls) throws InterruptedException {
        return new BoundedCycleSolver().solve(net, CycleSolveLimits.DEFAULT,
            new PlanningBudget(calls::incrementAndGet, Long.MAX_VALUE, Long.MAX_VALUE, () -> 0L));
    }

    /** NeoECO rejects an unsupported recipe after the dedup checkpoints and the pattern limit, before any matrix work. */
    @Test
    void callerRejectionIsReportedWhereNeoEcoReportsIt() throws Exception {
        AtomicLong calls = new AtomicLong();
        CycleNet.Builder builder = twoStepRing();
        builder.rejectTransition(1, "NOT_FAST_SUPPORTED");
        CycleSolution result = solveCounting(builder.build(), calls);
        assertEquals(CycleSolveStatus.UNSUPPORTED_PATTERN, result.status());
        assertEquals(CycleSolveDiagnostic.Code.UNSUPPORTED_PATTERN, result.diagnostics().get(0).code());
        assertEquals(1 + 2, calls.get(), "solve + one dedup checkpoint per declared view");

        CycleNet.Builder wide = twoStepRing();
        wide.rejectTransition(0, "AMOUNT_UNREPRESENTABLE: input=k amount=1 max=1");
        assertEquals(CycleSolveStatus.UNREPRESENTABLE, solveCounting(wide.build(), new AtomicLong()).status());
    }

    /** A per-recipe total beyond the long range surfaces in NeoECO's matrix loop, after that recipe's checkpoint. */
    @Test
    void unrepresentableRecipeTotalsAreReportedInTheMatrixLoop() throws Exception {
        AtomicLong calls = new AtomicLong();
        CycleNet.Builder builder = twoStepRing();
        builder.unrepresentableTotals(1, "input total for m amount=2^64 max=" + Long.MAX_VALUE);
        CycleSolution result = solveCounting(builder.build(), calls);
        assertEquals(CycleSolveStatus.UNREPRESENTABLE, result.status());
        assertTrue(result.exceedsLongRange());
        assertEquals(1 + 2 + 2, calls.get(), "solve + dedup(2) + matrix checkpoints up to and including recipe 1");
    }

    @Test
    void representableResultsDoNotCarryTheMarker() throws Exception {
        CycleSolution result = new BoundedCycleSolver().solve(doubling().build(), CycleSolveLimits.DEFAULT,
            new PlanningBudget(Cancellation.NONE));
        assertFalse(result.exceedsLongRange());
    }
}
