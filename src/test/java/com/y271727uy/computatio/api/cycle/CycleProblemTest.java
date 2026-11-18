package com.y271727uy.computatio.api.cycle;

import static org.junit.jupiter.api.Assertions.*;

import com.y271727uy.computatio.algorithm.amount.PlannerAmount;
import com.y271727uy.computatio.algorithm.cycle.Cancellation;
import com.y271727uy.computatio.algorithm.cycle.CycleNet;
import com.y271727uy.computatio.algorithm.cycle.CycleSolution;
import com.y271727uy.computatio.algorithm.cycle.CycleSolveDiagnostic;
import com.y271727uy.computatio.algorithm.cycle.CycleSolveLimits;
import com.y271727uy.computatio.algorithm.cycle.CycleSolveStatus;
import com.y271727uy.computatio.algorithm.cycle.PlanningBudget;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CycleProblemTest {
    private static CycleSolution solve(CycleProblem<String, String> problem) throws InterruptedException {
        return problem.solve(CycleSolveLimits.DEFAULT, new PlanningBudget(Cancellation.NONE));
    }

    /** Members first, then each recipe's inputs and gross outputs (recipes by id), then required keys. */
    @Test
    void keysAreIndexedInNeoEcoOrder() {
        CycleProblem<String, String> problem = CycleProblem.<String, String>builder()
            .member("bucket")
            .required("target", 5).required("bucket", 1)
            .recipe("pour", 2).input("water_bucket", 1).output("bucket", 1).output("target", 1).add()
            .recipe("fill", 1).input("bucket", 1).input("water", 1000).output("water_bucket", 1).add()
            .build();
        assertEquals(List.of("bucket", "water", "water_bucket", "target"), problem.keys());
        assertEquals(List.of("fill", "pour"), problem.recipes(), "recipes ordered by id");
    }

    /** Several views of one recipe: the lowest id wins, and every view still costs a dedup checkpoint. */
    @Test
    void duplicateViewsAreDeduplicatedByIdentityAndCounted() {
        CycleProblem<String, String> problem = CycleProblem.<String, String>builder()
            .member("x").required("x", 10).stock("x", 1)
            .recipe("double", 7).input("x", 1).output("x", 2).add()
            .recipe("double", 3).input("x", 1).output("x", 3).add()
            .build();
        assertEquals(1, problem.net().transitionCount());
        assertEquals(2, problem.net().declaredTransitionCount());
        assertEquals(3, problem.net().produced(0, 0), "id 3 is the surviving view");
    }

    @Test
    void boundaryKeysAreImportableUnlessTheyAreMembers() {
        CycleProblem<String, String> problem = CycleProblem.<String, String>builder()
            .member("a").boundary("a").boundary("ore")
            .recipe("r", 0).input("a", 1).input("ore", 1).output("a", 2).add()
            .build();
        assertFalse(problem.net().boundary(problem.keyIndex("a")));
        assertTrue(problem.net().boundary(problem.keyIndex("ore")));
    }

    @Test
    void negativeStockAndTargetsClampToZero() {
        CycleProblem<String, String> problem = CycleProblem.<String, String>builder()
            .member("x").stock("x", -5).required("x", PlannerAmount.of(-3))
            .recipe("r", 0).input("x", 1).output("x", 2).add()
            .build();
        assertEquals(PlannerAmount.ZERO, problem.net().stock(0));
        assertEquals(PlannerAmount.ZERO, problem.net().required(0));
    }

    /** NeoECO reports a summed input/output beyond the long range as UNREPRESENTABLE in its matrix pass. */
    @Test
    void recipeTotalsBeyondLongRangeAreUnrepresentable() throws Exception {
        CycleProblem<String, String> problem = CycleProblem.<String, String>builder()
            .member("x").required("x", 10).stock("x", 1)
            .recipe("huge", 0).input("x", Long.MAX_VALUE).input("x", 1).output("x", 1).add()
            .build();
        CycleSolution result = solve(problem);
        assertEquals(CycleSolveStatus.UNREPRESENTABLE, result.status());
        assertTrue(result.diagnostics().get(0).message().contains("input total for x"), result.summary());
    }

    @Test
    void callerRejectionsReachTheSolver() throws Exception {
        CycleProblem<String, String> problem = CycleProblem.<String, String>builder()
            .member("x").required("x", 10).stock("x", 1)
            .recipe("fuzzy", 0).input("x", 1).output("x", 2).reject("NOT_FAST_SUPPORTED").add()
            .build();
        assertEquals(CycleSolveStatus.UNSUPPORTED_PATTERN, solve(problem).status());
    }

    /** A returned container counts as a second feedback key for NeoECO's growth profile. */
    @Test
    void inputRemaindersFeedTheGrowthProfile() throws Exception {
        CycleProblem<String, String> withBucket = CycleProblem.<String, String>builder()
            .member("x").required("x", 64).stock("x", 1).stock("bucket", 1)
            .recipe("grow", 0).input("x", 1).input("bucket", PlannerAmount.ONE, "bucket", PlannerAmount.ONE).output("x", 2).add()
            .build();
        assertEquals(1, withBucket.net().growthRemainder(0, withBucket.keyIndex("bucket")));
        assertNull(withBucket.solveGrowth(CycleSolveLimits.DEFAULT, new PlanningBudget(Cancellation.NONE)));

        CycleProblem<String, String> plain = CycleProblem.<String, String>builder()
            .member("x").required("x", 64).stock("x", 1)
            .recipe("grow", 0).input("x", 1).output("x", 2).add()
            .build();
        CycleSolution growth = plain.solveGrowth(CycleSolveLimits.DEFAULT, new PlanningBudget(Cancellation.NONE));
        assertNotNull(growth);
        assertEquals(Map.of("grow", PlannerAmount.of(63)), plain.firings(growth));
    }

    @Test
    void nonPositiveNamedRemainderMakesTheRecipeGrowthUnsafe() {
        CycleProblem<String, String> problem = CycleProblem.<String, String>builder()
            .member("x").required("x", 64).stock("x", 1)
            .recipe("grow", 0).input("x", PlannerAmount.ONE, "x", PlannerAmount.ZERO).output("x", 2).add()
            .build();
        assertFalse(problem.net().growthSafe(0));
    }

    /** The answer comes back keyed by the caller's own recipes and keys. */
    @Test
    void answersAreMappedBackToCallerTypes() throws Exception {
        CycleProblem<String, String> problem = CycleProblem.<String, String>builder()
            .member("bucket").member("water_bucket").boundary("water").stock("bucket", 1).required("concrete", 64)
            .recipe("fill", 0).input("bucket", 1).input("water", 1000).output("water_bucket", 1).add()
            .recipe("pour", 1).input("water_bucket", 1).output("bucket", 1).output("concrete", 1).add()
            .build();
        CycleSolution result = solve(problem);
        assertEquals(CycleSolveStatus.SUCCESS, result.status(), result.summary());
        assertEquals(Map.of("fill", PlannerAmount.of(64), "pour", PlannerAmount.of(64)), problem.firings(result));
        assertEquals(Map.of("water", PlannerAmount.of(64_000)), problem.externalDemand(result));
        assertEquals(Map.of("bucket", PlannerAmount.ONE), problem.requiredSeed(result));
        assertTrue(problem.seedShortfall(result).isEmpty());
        assertEquals(PlannerAmount.of(64), problem.deliverable(result).get("concrete"));
        assertEquals(CycleSolveStatus.SUCCESS, CycleProblem.longStatus(result));
    }

    @Test
    void verifyFiringsFollowsNeoEcoMapSemanticsWithCallerRecipes() throws Exception {
        CycleProblem<String, String> problem = CycleProblem.<String, String>builder()
            .member("a").member("b").stock("a", 1).required("a", 10)
            .recipe("ab", 0).input("a", 1).output("b", 1).add()
            .recipe("ba", 1).input("b", 1).output("a", 2).add()
            .recipe("idle", 2).input("a", 1).output("b", 1).add()
            .build();
        Map<String, PlannerAmount> exact = Map.of("ab", PlannerAmount.of(9), "ba", PlannerAmount.of(9));
        assertNotNull(problem.verifyFirings(exact, CycleSolveLimits.DEFAULT, Cancellation.NONE));
        assertNull(problem.verifyFirings(Map.of("ab", PlannerAmount.of(9), "ba", PlannerAmount.of(9), "idle",
            PlannerAmount.ZERO), CycleSolveLimits.DEFAULT, Cancellation.NONE), "explicit zero");
        assertNull(problem.verifyFirings(Map.of("ab", PlannerAmount.of(9), "ba", PlannerAmount.of(9), "elsewhere",
            PlannerAmount.of(4)), CycleSolveLimits.DEFAULT, Cancellation.NONE), "recipe outside the component");
    }

    @Test
    void wideAnswersReportUnrepresentableForLongCallers() throws Exception {
        CycleProblem<String, String> problem = CycleProblem.<String, String>builder()
            .member("a").member("b").member("c").required("a", PlannerAmount.of(BigInteger.TEN.pow(30)))
            .recipe("ab", 0).input("a", 1).output("b", 1).growthSafe(false).add()
            .recipe("bc", 1).input("b", 1).output("c", 1).growthSafe(false).add()
            .recipe("ca", 2).input("c", 1).output("a", 2).growthSafe(false).add()
            .build();
        CycleSolution result = solve(problem);
        assertTrue(result.exceedsLongRange());
        assertEquals(CycleSolveStatus.UNREPRESENTABLE, CycleProblem.longStatus(result));
        assertTrue(result.diagnostics().stream().anyMatch(d ->
            d.code() == CycleSolveDiagnostic.Code.EXECUTION_AMOUNT_UNREPRESENTABLE));
        CycleNet net = problem.net();
        assertEquals(3, net.transitionCount());
    }
}
