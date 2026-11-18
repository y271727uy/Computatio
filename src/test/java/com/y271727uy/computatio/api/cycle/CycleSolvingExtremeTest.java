package com.y271727uy.computatio.api.cycle;

import static org.junit.jupiter.api.Assertions.*;

import com.y271727uy.computatio.algorithm.amount.PlannerAmount;
import com.y271727uy.computatio.algorithm.cycle.Cancellation;
import com.y271727uy.computatio.algorithm.cycle.CycleNet;
import com.y271727uy.computatio.algorithm.cycle.CycleSolution;
import com.y271727uy.computatio.algorithm.cycle.CycleSolveLimits;
import com.y271727uy.computatio.algorithm.cycle.CycleSolveStatus;
import com.y271727uy.computatio.algorithm.cycle.PlanningBudget;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class CycleSolvingExtremeTest {
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

    // ------------------------------------------------------------------------------------------------
    // Differential testing against exhaustive reachability
    // ------------------------------------------------------------------------------------------------

    /**
     * Random small nets whose state space is finite (every firing burns one unit of finite fuel). Exhaustive BFS is
     * the ground truth: SUCCESS must be reachable and replayable, INSUFFICIENT_EXTERNAL_INPUT must be unreachable.
     */
    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void randomNetsNeverClaimFalseSuccessOrFalseInfeasibility() throws Exception {
        Random random = new Random(20261117);
        Map<CycleSolveStatus, Integer> outcomes = new EnumMap<>(CycleSolveStatus.class);
        int samples = Integer.parseInt(System.getenv().getOrDefault("COMPUTATIO_CYCLE_SAMPLES", "3000"));
        for (int sample = 0; sample < samples; sample++) {
            CycleNet net = randomNet(random);
            CycleSolution result = CycleSolving.solve(net, CycleSolveLimits.DEFAULT,
                new PlanningBudget(Cancellation.NONE, 2_000_000, Long.MAX_VALUE, () -> 0L));
            outcomes.merge(result.status(), 1, Integer::sum);
            boolean reachable = reachable(net);
            String context = "sample=" + sample + " " + describe(net) + " -> " + result.summary();
            switch (result.status()) {
                case SUCCESS:
                    assertTrue(reachable, "false SUCCESS: " + context);
                    assertReplays(net, result, context);
                    break;
                case INSUFFICIENT_EXTERNAL_INPUT:
                    assertFalse(reachable, "false INSUFFICIENT: " + context);
                    break;
                case UNKNOWN_BUDGET:
                    break;
                default:
                    fail("unexpected status: " + context);
            }
        }
        System.out.println("[CycleSolvingExtremeTest] random outcomes over " + samples + " nets: " + outcomes);
    }

    // ------------------------------------------------------------------------------------------------
    // Extreme magnitudes
    // ------------------------------------------------------------------------------------------------

    @Test
    void catalystConsumingLongMaxValuePerFiring() throws Exception {
        // keys: 0 catalyst (member), 1 product. One firing needs Long.MAX_VALUE catalyst and returns it all.
        CycleNet.Builder builder = CycleNet.builder(2).member(0).stock(0, Long.MAX_VALUE)
            .required(1, PlannerAmount.of(BigInteger.TEN.pow(30)));
        builder.addTransition("catalysed", new long[] {Long.MAX_VALUE, 0}, new long[] {Long.MAX_VALUE, 1});
        CycleNet net = builder.build();
        CycleSolution result = CycleSolving.solve(net);
        assertEquals(CycleSolveStatus.SUCCESS, result.status(), result.summary());
        assertEquals(BigInteger.TEN.pow(30), result.firingsOf(0).toBigInteger());
        assertEquals(LONG_MAX, result.requiredSeed().get(0).toBigInteger());
        assertBalance(net, result);
    }

    @Test
    void catalystOneShortOfLongMaxValueIsAnExactShortfall() throws Exception {
        CycleNet.Builder builder = CycleNet.builder(2).member(0).stock(0, Long.MAX_VALUE - 1).required(1, 5);
        builder.addTransition("catalysed", new long[] {Long.MAX_VALUE, 0}, new long[] {Long.MAX_VALUE, 1});
        CycleSolution result = CycleSolving.solve(builder.build());
        assertEquals(CycleSolveStatus.INSUFFICIENT_EXTERNAL_INPUT, result.status(), result.summary());
        assertEquals(PlannerAmount.ONE, result.seedShortfall().get(0));
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void ringTargetOfTwoToTheFiveThousand() throws Exception {
        BigInteger target = BigInteger.ONE.shiftLeft(5000);
        CycleNet net = ring(3, PlannerAmount.of(target), PlannerAmount.ONE);
        CycleSolution result = CycleSolving.solve(net);
        assertEquals(CycleSolveStatus.SUCCESS, result.status(), result.summary());
        for (int t = 0; t < 3; t++) assertEquals(target.subtract(BigInteger.ONE), result.firingsOf(t).toBigInteger());
        assertBalance(net, result);
    }

    @Test
    void googolStockPlusSevenNeedsSevenDoublings() throws Exception {
        BigInteger googol = BigInteger.TEN.pow(100);
        CycleNet.Builder builder = CycleNet.builder(1).member(0).stock(0, PlannerAmount.of(googol))
            .required(0, PlannerAmount.of(googol.add(BigInteger.valueOf(7))));
        builder.addTransition("double", new long[] {1}, new long[] {2});
        CycleSolution result = CycleSolving.solve(builder.build());
        assertEquals(CycleSolveStatus.SUCCESS, result.status(), result.summary());
        assertEquals(PlannerAmount.of(7), result.firingsOf(0));
    }

    @Test
    void longMaxTargetWithoutSeedIsAnExactShortfall() throws Exception {
        CycleSolution result = CycleSolving.solve(ring(2, PlannerAmount.of(Long.MAX_VALUE), PlannerAmount.ZERO));
        assertEquals(CycleSolveStatus.INSUFFICIENT_EXTERNAL_INPUT, result.status(), result.summary());
        assertEquals(PlannerAmount.ONE, result.seedShortfall().get(0));
    }

    @Test
    void fluidScaledContainerLoopReportsDemandBeyondLongRange() throws Exception {
        // keys: 0 bucket, 1 water bucket, 2 water mB (boundary), 3 concrete (target). 1000 mB per bucket.
        BigInteger target = BigInteger.TEN.pow(18).multiply(BigInteger.TEN);
        CycleNet.Builder builder = CycleNet.builder(4).member(0).member(1).boundary(2).stock(0, 1)
            .required(3, PlannerAmount.of(target));
        builder.addTransition("fill", new long[] {1, 0, 1000, 0}, new long[] {0, 1, 0, 0});
        builder.addTransition("pour", new long[] {0, 1, 0, 0}, new long[] {1, 0, 0, 1});
        CycleNet net = builder.build();
        CycleSolution result = CycleSolving.solve(net);
        assertEquals(CycleSolveStatus.SUCCESS, result.status(), result.summary());
        assertEquals(target.multiply(BigInteger.valueOf(1000)), result.externalDemand().get(2).toBigInteger());
        assertFalse(result.externalDemand().get(2).fitsLong());
        assertEquals(PlannerAmount.ONE, result.requiredSeed().get(0));
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void thirtyStepRingWithAHugeTarget() throws Exception {
        CycleNet net = ring(30, PlannerAmount.of(BigInteger.TEN.pow(40)), PlannerAmount.ONE);
        long started = System.nanoTime();
        CycleSolution result = CycleSolving.solve(net, CycleSolveLimits.DEFAULT,
            new PlanningBudget(Cancellation.NONE, Long.MAX_VALUE, TimeUnit.SECONDS.toNanos(100), System::nanoTime));
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        System.out.println("[CycleSolvingExtremeTest] 30-step ring, target 1e40: " + result.status() + " in " + millis + " ms");
        assertEquals(CycleSolveStatus.SUCCESS, result.status(), result.summary());
        assertBalance(net, result);
    }

    @Test
    void wideFanInRecipeWithFiveHundredBoundaryIngredients() throws Exception {
        int keys = 502; // 0 seed (member), 1 product (target), 2..501 boundary ingredients
        CycleNet.Builder builder = CycleNet.builder(keys).member(0).stock(0, 1).required(1, 1_000_000);
        long[] in = new long[keys];
        long[] out = new long[keys];
        in[0] = 1;
        out[0] = 1;
        out[1] = 1;
        for (int k = 2; k < keys; k++) {
            builder.boundary(k);
            in[k] = k;
        }
        builder.addTransition("assemble", in, out);
        CycleSolution result = CycleSolving.solve(builder.build());
        assertEquals(CycleSolveStatus.SUCCESS, result.status(), result.summary());
        assertEquals(PlannerAmount.of(1_000_000), result.firingsOf(0));
        for (int k = 2; k < keys; k++) assertEquals(PlannerAmount.of(1_000_000L * k), result.externalDemand().get(k));
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void searchOnlyGrowthRingStaysHonestForATrillionTarget() throws Exception {
        CycleNet.Builder builder = CycleNet.builder(2).member(0).member(1).stock(0, 1).required(0, 1_000_000_000_000L);
        builder.addTransition("a-to-b", new long[] {1, 0}, new long[] {0, 1}, false, false);
        builder.addTransition("b-to-2a", new long[] {0, 1}, new long[] {2, 0}, false, false);
        CycleNet net = builder.build();
        CycleSolution result = CycleSolving.solve(net, CycleSolveLimits.DEFAULT,
            new PlanningBudget(Cancellation.NONE, Long.MAX_VALUE, TimeUnit.SECONDS.toNanos(100), System::nanoTime));
        System.out.println("[CycleSolvingExtremeTest] search-only growth ring, target 1e12: " + result.summary());
        assertTrue(result.status() == CycleSolveStatus.SUCCESS || result.status() == CycleSolveStatus.UNKNOWN_BUDGET,
            result.summary());
        if (result.status() == CycleSolveStatus.SUCCESS) assertBalance(net, result);
    }

    @Test
    void zeroTargetIsSatisfiedWithoutFiring() throws Exception {
        CycleSolution result = CycleSolving.solve(ring(4, PlannerAmount.ZERO, PlannerAmount.ZERO));
        assertEquals(CycleSolveStatus.SUCCESS, result.status());
        assertEquals(PlannerAmount.ZERO, result.totalFirings());
    }

    // ------------------------------------------------------------------------------------------------
    // Facade behaviour
    // ------------------------------------------------------------------------------------------------

    @Test
    void asyncSolveCompletesAndCancellationIsHonoured() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            CompletableFuture<CycleSolution> done = CycleSolving.solveAsync(
                ring(3, PlannerAmount.of(1000), PlannerAmount.ONE), executor);
            assertEquals(CycleSolveStatus.SUCCESS, done.get(30, TimeUnit.SECONDS).status());

            CompletableFuture<CycleSolution> cancelled = CycleSolving.solveAsync(
                ring(3, PlannerAmount.of(1000), PlannerAmount.ONE), executor);
            cancelled.cancel(true);
            assertTrue(cancelled.isCancelled());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void materialBalanceFacadeMatchesTheSolver() throws Exception {
        var result = CycleSolving.materialBalance(new long[][] {{1, 0}}, new long[][] {{0, 2}}, new boolean[2],
            new PlannerAmount[] {PlannerAmount.of(Long.MAX_VALUE), PlannerAmount.ZERO},
            new PlannerAmount[] {PlannerAmount.ZERO, PlannerAmount.of(LONG_MAX.multiply(BigInteger.TWO))},
            Cancellation.NONE);
        assertEquals(LONG_MAX, result.counts()[0].toBigInteger());
    }

    // ------------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------------

    private static CycleNet ring(int size, PlannerAmount target, PlannerAmount stock) {
        CycleNet.Builder builder = CycleNet.builder(size);
        for (int k = 0; k < size; k++) builder.member(k);
        builder.stock(0, stock).required(0, target);
        for (int t = 0; t < size; t++) {
            long[] in = new long[size];
            long[] out = new long[size];
            in[t] = 1;
            out[(t + 1) % size] += t == size - 1 ? 2 : 1;
            builder.addTransition("ring" + t, in, out, true, false);
        }
        return builder.build();
    }

    /** keys 0..2 materials, 3 finite fuel burnt by every firing, 4 optional boundary ingredient. */
    private static CycleNet randomNet(Random random) {
        int keys = 5;
        CycleNet.Builder builder = CycleNet.builder(keys).keyName(3, "fuel").keyName(4, "import").boundary(4);
        for (int k = 0; k < 3; k++) {
            builder.stock(k, random.nextInt(4));
            if (random.nextInt(3) == 0) builder.member(k);
        }
        builder.stock(3, random.nextInt(6));
        int targets = 1 + random.nextInt(2);
        for (int i = 0; i < targets; i++) builder.required(random.nextInt(3), random.nextInt(9));
        int transitions = 1 + random.nextInt(3);
        for (int t = 0; t < transitions; t++) {
            long[] in = new long[keys];
            long[] out = new long[keys];
            for (int k = 0; k < 3; k++) {
                if (random.nextInt(2) == 0) in[k] = random.nextInt(3);
                if (random.nextInt(2) == 0) out[k] = random.nextInt(4);
            }
            if (Arrays.stream(out, 0, 3).sum() == 0) out[random.nextInt(3)] = 1 + random.nextInt(3);
            in[3] = 1;
            if (random.nextInt(3) == 0) in[4] = 1 + random.nextInt(3);
            builder.addTransition("t" + t, in, out, random.nextInt(4) != 0, random.nextBoolean());
        }
        return builder.build();
    }

    /** Exhaustive BFS; boundary keys are unlimited. */
    private static boolean reachable(CycleNet net) {
        int n = net.keyCount();
        long[] start = new long[n];
        for (int k = 0; k < n; k++) start[k] = net.stock(k).longValueExact();
        ArrayDeque<long[]> queue = new ArrayDeque<>();
        Set<List<Long>> seen = new HashSet<>();
        queue.add(start);
        seen.add(key(start));
        while (!queue.isEmpty()) {
            long[] marking = queue.poll();
            if (covers(net, marking)) return true;
            for (int t = 0; t < net.transitionCount(); t++) {
                boolean enabled = true;
                for (int k = 0; k < n; k++) {
                    if (!internal(net, k)) continue;
                    if (marking[k] < net.consumed(t, k)) { enabled = false; break; }
                }
                if (!enabled) continue;
                long[] next = marking.clone();
                for (int k = 0; k < n; k++) {
                    if (!internal(net, k)) continue;
                    next[k] = next[k] - net.consumed(t, k) + net.produced(t, k);
                }
                if (seen.add(key(next))) queue.add(next);
            }
        }
        return false;
    }

    private static boolean internal(CycleNet net, int key) {
        return !net.boundary(key) || net.member(key);
    }

    private static boolean covers(CycleNet net, long[] marking) {
        for (int k = 0; k < net.keyCount(); k++) {
            if (!internal(net, k)) continue;
            if (BigInteger.valueOf(marking[k]).compareTo(net.required(k).toBigInteger()) < 0) return false;
        }
        return true;
    }

    private static List<Long> key(long[] marking) {
        Long[] boxed = new Long[marking.length];
        for (int i = 0; i < marking.length; i++) boxed[i] = marking[i];
        return Arrays.asList(boxed);
    }

    /** Sequential replay of the expanded plan from stock, importing boundary deficits on demand. */
    private static void assertReplays(CycleNet net, CycleSolution result, String context) {
        int[] steps = result.expandedPlan(1_000_000);
        assertNotNull(steps, "plan too long to replay: " + context);
        int n = net.keyCount();
        BigInteger[] marking = new BigInteger[n];
        BigInteger[] imported = new BigInteger[n];
        long[] fired = new long[net.transitionCount()];
        for (int k = 0; k < n; k++) {
            marking[k] = net.stock(k).toBigInteger();
            imported[k] = BigInteger.ZERO;
        }
        for (int t : steps) {
            fired[t]++;
            for (int k = 0; k < n; k++) {
                BigInteger need = BigInteger.valueOf(net.consumed(t, k));
                if (marking[k].compareTo(need) < 0) {
                    assertFalse(internal(net, k), "replay went negative on key " + k + ": " + context);
                    BigInteger missing = need.subtract(marking[k]);
                    imported[k] = imported[k].add(missing);
                    marking[k] = marking[k].add(missing);
                }
                marking[k] = marking[k].subtract(need).add(BigInteger.valueOf(net.produced(t, k)));
            }
        }
        for (int k = 0; k < n; k++) {
            assertTrue(marking[k].compareTo(net.required(k).toBigInteger()) >= 0, "target missed on key " + k + ": " + context);
            if (!internal(net, k) && !result.externalDemand().isEmpty()) {
                assertEquals(result.externalDemand().get(k).toBigInteger(), imported[k], "import mismatch: " + context);
            }
        }
        for (int t = 0; t < fired.length; t++) assertEquals(result.firingsOf(t).longValueExact(), fired[t], context);
    }

    private static void assertBalance(CycleNet net, CycleSolution result) {
        for (int k = 0; k < net.keyCount(); k++) {
            if (net.boundary(k) && !net.member(k)) continue;
            BigInteger balance = net.stock(k).toBigInteger();
            for (int t = 0; t < net.transitionCount(); t++) {
                balance = balance.add(result.firingsOf(t).toBigInteger()
                    .multiply(BigInteger.valueOf(net.produced(t, k)).subtract(BigInteger.valueOf(net.consumed(t, k)))));
            }
            assertTrue(balance.compareTo(net.required(k).toBigInteger()) >= 0, "key " + k);
        }
    }

    private static String describe(CycleNet net) {
        StringBuilder builder = new StringBuilder("net{");
        for (int k = 0; k < net.keyCount(); k++) {
            builder.append(net.keyName(k)).append(":stock=").append(net.stock(k)).append(",req=").append(net.required(k))
                .append(net.boundary(k) ? ",B" : "").append(net.member(k) ? ",M" : "").append(' ');
        }
        for (int t = 0; t < net.transitionCount(); t++) {
            builder.append(net.transitionName(t)).append(net.circuitSafe(t) ? "" : "(search)").append(":in=[");
            for (int k = 0; k < net.keyCount(); k++) builder.append(net.consumed(t, k)).append(k + 1 < net.keyCount() ? "," : "");
            builder.append("] out=[");
            for (int k = 0; k < net.keyCount(); k++) builder.append(net.produced(t, k)).append(k + 1 < net.keyCount() ? "," : "");
            builder.append("] ");
        }
        return builder.append('}').toString();
    }
}
