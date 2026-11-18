package com.y271727uy.computatio.api.cycle;

import static org.junit.jupiter.api.Assertions.*;

import com.y271727uy.computatio.algorithm.amount.PlannerAmount;
import com.y271727uy.computatio.algorithm.cycle.BoundedCycleSolver;
import com.y271727uy.computatio.algorithm.cycle.Cancellation;
import com.y271727uy.computatio.algorithm.cycle.CycleNet;
import com.y271727uy.computatio.algorithm.cycle.CycleSolution;
import com.y271727uy.computatio.algorithm.cycle.CycleSolveLimits;
import com.y271727uy.computatio.algorithm.cycle.CycleSolveStatus;
import com.y271727uy.computatio.algorithm.cycle.PlanningBudget;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Second-round stress: bigger oracle nets, metamorphic relations, concurrency, budgets. */
class CycleSolvingStressTest {
    private static final int SAMPLES = Integer.parseInt(System.getenv().getOrDefault("COMPUTATIO_STRESS_SAMPLES", "1500"));

    static final java.util.concurrent.atomic.AtomicLong EXHAUSTED = new java.util.concurrent.atomic.AtomicLong();
    static final java.util.concurrent.atomic.AtomicLong SLOWEST_NANOS = new java.util.concurrent.atomic.AtomicLong();
    static volatile String slowest = "";

    /** Work-bounded (deterministic) solve; a shared-budget exhaustion is reported as UNKNOWN_BUDGET. */
    private static CycleSolution solve(CycleNet net) throws InterruptedException {
        return solve(net, new PlanningBudget(Cancellation.NONE, 2_000_000, Long.MAX_VALUE, () -> 0L));
    }

    private static CycleSolution solve(CycleNet net, PlanningBudget budget) throws InterruptedException {
        long started = System.nanoTime();
        try {
            return CycleSolving.solve(net, CycleSolveLimits.DEFAULT, budget);
        } catch (PlanningBudget.Exhausted exhausted) {
            EXHAUSTED.incrementAndGet();
            return CycleSolution.failure(CycleSolveStatus.UNKNOWN_BUDGET,
                com.y271727uy.computatio.algorithm.cycle.CycleSolveDiagnostic.Code.STATE_EQUATION_BUDGET,
                exhausted.getMessage());
        } finally {
            long spent = System.nanoTime() - started;
            if (spent > SLOWEST_NANOS.get()) {
                synchronized (CycleSolvingStressTest.class) {
                    if (spent > SLOWEST_NANOS.get()) {
                        SLOWEST_NANOS.set(spent);
                        slowest = describe(net);
                    }
                }
            }
        }
    }

    @org.junit.jupiter.api.AfterEach
    void reportBudget(org.junit.jupiter.api.TestInfo info) {
        System.out.printf("[Stress] %s: shared-budget exhaustions=%d, slowest solve %.1f ms on %s%n",
            info.getDisplayName(), EXHAUSTED.getAndSet(0), SLOWEST_NANOS.getAndSet(0) / 1e6, slowest);
        slowest = "";
    }

    static String describe(CycleNet net) {
        StringBuilder b = new StringBuilder("{");
        for (int k = 0; k < net.keyCount(); k++) {
            b.append(k).append(":s=").append(net.stock(k)).append(",r=").append(net.required(k))
                .append(net.boundary(k) ? ",B" : "").append(net.member(k) ? ",M" : "").append(' ');
        }
        for (int t = 0; t < net.transitionCount(); t++) {
            b.append("| t").append(t).append(net.circuitSafe(t) ? "" : "!c").append(net.growthSafe(t) ? "" : "!g").append(" in=");
            for (int k = 0; k < net.keyCount(); k++) b.append(net.consumed(t, k)).append(k + 1 < net.keyCount() ? "," : "");
            b.append(" out=");
            for (int k = 0; k < net.keyCount(); k++) b.append(net.produced(t, k)).append(k + 1 < net.keyCount() ? "," : "");
            b.append(' ');
        }
        return b.append('}').toString();
    }

    // ------------------------------------------------------------------------------------------------
    // 1. Bigger nets against exhaustive BFS
    // ------------------------------------------------------------------------------------------------

    @Test
    @Timeout(value = 1800, unit = TimeUnit.SECONDS)
    void biggerRandomNetsAgreeWithExhaustiveSearch() throws Exception {
        Random random = new Random(0xB16B00B5L);
        Map<CycleSolveStatus, Integer> outcomes = new EnumMap<>(CycleSolveStatus.class);
        long solverNanos = 0;
        long worstNanos = 0;
        for (int sample = 0; sample < SAMPLES; sample++) {
            CycleNet net = randomNet(random, 4, 2 + random.nextInt(4), 6, 9);
            long started = System.nanoTime();
            CycleSolution result = solve(net);
            long spent = System.nanoTime() - started;
            solverNanos += spent;
            worstNanos = Math.max(worstNanos, spent);
            outcomes.merge(result.status(), 1, Integer::sum);
            checkAgainstOracle(net, result, "sample=" + sample);
        }
        System.out.printf("[Stress] bigger random nets x%d: %s, avg %.2f ms, worst %.1f ms%n", SAMPLES, outcomes,
            solverNanos / 1e6 / SAMPLES, worstNanos / 1e6);
    }

    // ------------------------------------------------------------------------------------------------
    // 2. Metamorphic relations (no oracle needed, so nets can be large and fuel-free)
    // ------------------------------------------------------------------------------------------------

    @Test
    @Timeout(value = 1800, unit = TimeUnit.SECONDS)
    void relabellingKeysAndRecipesNeverFlipADefiniteVerdict() throws Exception {
        Random random = new Random(31337);
        int flips = 0;
        int unknownMismatch = 0;
        for (int sample = 0; sample < SAMPLES; sample++) {
            CycleNet net = randomNet(random, 5, 1 + random.nextInt(5), 0, 40);
            CycleNet permuted = permute(net, random);
            CycleSolution a = solve(net);
            CycleSolution b = solve(permuted);
            if (definite(a) && definite(b)) {
                if (a.status() != b.status()) flips++;
                assertEquals(a.status(), b.status(), "relabel flipped verdict: " + a.summary() + " vs " + b.summary());
            } else if (a.status() != b.status()) {
                unknownMismatch++;
            }
        }
        System.out.printf("[Stress] relabel x%d: definite flips=%d, unknown-vs-definite=%d%n", SAMPLES, flips,
            unknownMismatch);
    }

    @Test
    @Timeout(value = 1800, unit = TimeUnit.SECONDS)
    void moreStockOrSmallerTargetNeverTurnsSuccessIntoInfeasible() throws Exception {
        Random random = new Random(4242);
        int checked = 0;
        for (int sample = 0; sample < SAMPLES; sample++) {
            CycleNet net = randomNet(random, 5, 1 + random.nextInt(5), 0, 40);
            CycleSolution base = solve(net);
            if (base.status() == CycleSolveStatus.SUCCESS) {
                PlannerAmount[] stock = new PlannerAmount[net.keyCount()];
                PlannerAmount[] required = new PlannerAmount[net.keyCount()];
                for (int k = 0; k < net.keyCount(); k++) {
                    stock[k] = net.stock(k).add(PlannerAmount.of(random.nextInt(5)));
                    required[k] = net.required(k).subtract(PlannerAmount.of(random.nextInt(3))).max(PlannerAmount.ZERO);
                }
                for (CycleNet easier : List.of(net.withStock(stock), net.withRequired(required))) {
                    CycleSolution result = solve(easier);
                    assertNotEquals(CycleSolveStatus.INSUFFICIENT_EXTERNAL_INPUT, result.status(),
                        "easier instance became infeasible: " + result.summary());
                    checked++;
                }
            } else if (base.status() == CycleSolveStatus.INSUFFICIENT_EXTERNAL_INPUT) {
                PlannerAmount[] required = new PlannerAmount[net.keyCount()];
                for (int k = 0; k < net.keyCount(); k++) {
                    required[k] = net.required(k).signum() > 0 ? net.required(k).add(PlannerAmount.of(random.nextInt(7)))
                        : net.required(k);
                }
                CycleSolution result = solve(net.withRequired(required));
                assertNotEquals(CycleSolveStatus.SUCCESS, result.status(),
                    "harder instance became feasible: " + result.summary());
                checked++;
            }
        }
        System.out.printf("[Stress] monotonicity checks: %d%n", checked);
    }

    // ------------------------------------------------------------------------------------------------
    // 3. Scale: huge targets on random circuit-safe nets, verified by exact balance
    // ------------------------------------------------------------------------------------------------

    @Test
    @Timeout(value = 1800, unit = TimeUnit.SECONDS)
    void hugeTargetsOnRandomNetsStayExact() throws Exception {
        Random random = new Random(99);
        Map<CycleSolveStatus, Integer> outcomes = new EnumMap<>(CycleSolveStatus.class);
        long worst = 0;
        for (int sample = 0; sample < SAMPLES / 3; sample++) {
            CycleNet small = randomNet(random, 5, 1 + random.nextInt(5), 0, 40);
            PlannerAmount[] required = new PlannerAmount[small.keyCount()];
            BigInteger scale = BigInteger.TEN.pow(15 + random.nextInt(40));
            for (int k = 0; k < small.keyCount(); k++) required[k] = PlannerAmount.of(small.required(k).toBigInteger().multiply(scale));
            CycleNet net = small.withRequired(required);
            long started = System.nanoTime();
            CycleSolution result = solve(net,
                new PlanningBudget(Cancellation.NONE, 5_000_000, TimeUnit.SECONDS.toNanos(20), System::nanoTime));
            worst = Math.max(worst, System.nanoTime() - started);
            outcomes.merge(result.status(), 1, Integer::sum);
            if (result.status() == CycleSolveStatus.SUCCESS) assertExactBalance(net, result);
        }
        System.out.printf("[Stress] huge targets x%d: %s, worst %.1f ms%n", SAMPLES / 3, outcomes, worst / 1e6);
    }

    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void longRecipeChainsAndWideStateEquations() throws Exception {
        for (int length : new int[] {50, 100, 200}) {
            CycleNet net = longRing(length, PlannerAmount.of(BigInteger.TEN.pow(30)));
            long started = System.nanoTime();
            CycleSolution result = CycleSolving.solve(net, CycleSolveLimits.DEFAULT,
                new PlanningBudget(Cancellation.NONE, Long.MAX_VALUE, TimeUnit.SECONDS.toNanos(100), System::nanoTime));
            System.out.printf("[Stress] ring of %d recipes with side imports, target 1e30: %s in %.1f ms%n", length,
                result.status(), (System.nanoTime() - started) / 1e6);
            assertNotEquals(CycleSolveStatus.INSUFFICIENT_EXTERNAL_INPUT, result.status(), result.summary());
            if (result.status() == CycleSolveStatus.SUCCESS) assertExactBalance(net, result);
        }
    }

    // ------------------------------------------------------------------------------------------------
    // 4. Concurrency: shared default solver, many threads, identical answers
    // ------------------------------------------------------------------------------------------------

    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void parallelSolvesMatchSequentialSolves() throws Exception {
        Random random = new Random(777);
        List<CycleNet> nets = new ArrayList<>();
        for (int i = 0; i < 400; i++) nets.add(randomNet(random, 5, 1 + random.nextInt(5), 0, 30));
        List<String> sequential = new ArrayList<>();
        for (CycleNet net : nets) sequential.add(fingerprint(solve(net)));
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            for (int round = 0; round < 3; round++) {
                List<Future<String>> futures = new ArrayList<>();
                for (CycleNet net : nets) futures.add(pool.submit(() -> fingerprint(solve(net))));
                for (int i = 0; i < nets.size(); i++) {
                    assertEquals(sequential.get(i), futures.get(i).get(60, TimeUnit.SECONDS), "net " + i + " round " + round);
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------------------------------------
    // 5. Budgets: wall-clock overshoot and tiny memory caps
    // ------------------------------------------------------------------------------------------------

    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void wallClockBudgetIsRespectedOnAdversarialSearch() throws Exception {
        long budgetMillis = 50;
        long worstOvershoot = 0;
        int exhausted = 0;
        int unknown = 0;
        for (int i = 0; i < 40; i++) {
            CycleNet net = searchOnlyGrowth(3 + i % 4, 1_000_000_000_000L + i);
            long started = System.nanoTime();
            try {
                CycleSolution result = CycleSolving.solve(net, CycleSolveLimits.DEFAULT, new PlanningBudget(Cancellation.NONE,
                    Long.MAX_VALUE, TimeUnit.MILLISECONDS.toNanos(budgetMillis), System::nanoTime));
                assertNotEquals(CycleSolveStatus.INSUFFICIENT_EXTERNAL_INPUT, result.status(), result.summary());
                if (result.status() == CycleSolveStatus.UNKNOWN_BUDGET) unknown++;
            } catch (PlanningBudget.Exhausted expected) {
                exhausted++;
            }
            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            worstOvershoot = Math.max(worstOvershoot, millis - budgetMillis);
        }
        System.out.printf("[Stress] 50 ms budget: exhausted=%d unknown=%d worst overshoot=%d ms%n", exhausted, unknown,
            worstOvershoot);
        assertTrue(worstOvershoot < 500, "budget overshoot " + worstOvershoot + " ms");
    }

    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void tinyMemoryBudgetsDegradeToUnknownNeverToWrongAnswers() throws Exception {
        Random random = new Random(5150);
        Map<CycleSolveStatus, Integer> outcomes = new EnumMap<>(CycleSolveStatus.class);
        for (long cap : new long[] {1, 64, 1024, 16 * 1024}) {
            BoundedCycleSolver solver = new BoundedCycleSolver(cap);
            for (int sample = 0; sample < SAMPLES / 4; sample++) {
                CycleNet net = randomNet(random, 4, 2 + random.nextInt(4), 6, 9);
                CycleSolution result = solver.solve(net, CycleSolveLimits.DEFAULT,
                    new PlanningBudget(Cancellation.NONE, 2_000_000, Long.MAX_VALUE, () -> 0L));
                outcomes.merge(result.status(), 1, Integer::sum);
                checkAgainstOracle(net, result, "cap=" + cap + " sample=" + sample);
            }
        }
        System.out.printf("[Stress] tiny memory caps: %s%n", outcomes);
    }

    // ------------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------------

    private static boolean definite(CycleSolution result) {
        return result.status() == CycleSolveStatus.SUCCESS || result.status() == CycleSolveStatus.INSUFFICIENT_EXTERNAL_INPUT;
    }

    private static String fingerprint(CycleSolution result) {
        return result.status() + " " + result.firings() + " " + result.externalDemand() + " " + result.requiredSeed();
    }

    private static void checkAgainstOracle(CycleNet net, CycleSolution result, String tag) {
        boolean reachable = reachable(net);
        String context = tag + " -> " + result.summary();
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

    /**
     * {@code materials} free keys, then (if {@code fuel > 0}) a fuel key burnt once per firing, then one boundary key.
     * With fuel the state space is finite, so BFS is an oracle.
     */
    static CycleNet randomNet(Random random, int materials, int transitions, int fuel, int maxTarget) {
        boolean fuelled = fuel > 0;
        int fuelKey = materials;
        int importKey = materials + (fuelled ? 1 : 0);
        int keys = importKey + 1;
        CycleNet.Builder builder = CycleNet.builder(keys).keyName(importKey, "import").boundary(importKey);
        if (fuelled) builder.keyName(fuelKey, "fuel").stock(fuelKey, random.nextInt(fuel + 1));
        for (int k = 0; k < materials; k++) {
            builder.stock(k, random.nextInt(5));
            if (random.nextInt(3) == 0) builder.member(k);
        }
        int targets = 1 + random.nextInt(2);
        for (int i = 0; i < targets; i++) builder.required(random.nextInt(materials), random.nextInt(maxTarget + 1));
        for (int t = 0; t < transitions; t++) {
            long[] in = new long[keys];
            long[] out = new long[keys];
            for (int k = 0; k < materials; k++) {
                if (random.nextInt(2) == 0) in[k] = random.nextInt(3);
                if (random.nextInt(2) == 0) out[k] = random.nextInt(4);
            }
            if (Arrays.stream(out, 0, materials).sum() == 0) out[random.nextInt(materials)] = 1 + random.nextInt(3);
            if (fuelled) in[fuelKey] = 1;
            if (random.nextInt(3) == 0) in[importKey] = 1 + random.nextInt(3);
            builder.addTransition("t" + t, in, out, random.nextInt(4) != 0, random.nextBoolean());
        }
        return builder.build();
    }

    private static CycleNet permute(CycleNet net, Random random) {
        int n = net.keyCount();
        int m = net.transitionCount();
        int[] keyPerm = shuffled(n, random);
        int[] transPerm = shuffled(m, random);
        CycleNet.Builder builder = CycleNet.builder(n);
        for (int k = 0; k < n; k++) {
            int to = keyPerm[k];
            builder.keyName(to, net.keyName(k)).stock(to, net.stock(k)).required(to, net.required(k));
            if (net.boundary(k)) builder.boundary(to);
            if (net.member(k)) builder.member(to);
        }
        int[] inverse = new int[m];
        for (int t = 0; t < m; t++) inverse[transPerm[t]] = t;
        for (int slot = 0; slot < m; slot++) {
            int t = inverse[slot];
            long[] in = new long[n];
            long[] out = new long[n];
            for (int k = 0; k < n; k++) {
                in[keyPerm[k]] = net.consumed(t, k);
                out[keyPerm[k]] = net.produced(t, k);
            }
            builder.addTransition(net.transitionName(t), in, out, net.circuitSafe(t), net.growthSafe(t));
        }
        return builder.build();
    }

    private static int[] shuffled(int size, Random random) {
        int[] values = new int[size];
        for (int i = 0; i < size; i++) values[i] = i;
        for (int i = size - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int swap = values[i];
            values[i] = values[j];
            values[j] = swap;
        }
        return values;
    }

    /** Ring of {@code length} member keys; every step also imports a boundary ingredient; last step doubles. */
    private static CycleNet longRing(int length, PlannerAmount target) {
        int keys = length + 1;
        CycleNet.Builder builder = CycleNet.builder(keys).boundary(length).stock(0, 1).required(0, target);
        for (int k = 0; k < length; k++) builder.member(k);
        for (int t = 0; t < length; t++) {
            long[] in = new long[keys];
            long[] out = new long[keys];
            in[t] = 1;
            in[length] = 1 + t % 7;
            out[(t + 1) % length] = t == length - 1 ? 2 : 1;
            builder.addTransition("step" + t, in, out);
        }
        return builder.build();
    }

    /** {@code size}-step doubling ring with every recipe barred from circuit algebra. */
    private static CycleNet searchOnlyGrowth(int size, long target) {
        CycleNet.Builder builder = CycleNet.builder(size).stock(0, 1).required(0, target);
        for (int k = 0; k < size; k++) builder.member(k);
        for (int t = 0; t < size; t++) {
            long[] in = new long[size];
            long[] out = new long[size];
            in[t] = 1;
            out[(t + 1) % size] = t == size - 1 ? 2 : 1;
            builder.addTransition("g" + t, in, out, false, false);
        }
        return builder.build();
    }

    private static boolean internal(CycleNet net, int key) {
        return !net.boundary(key) || net.member(key);
    }

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
            boolean covered = true;
            for (int k = 0; k < n && covered; k++) {
                if (internal(net, k) && BigInteger.valueOf(marking[k]).compareTo(net.required(k).toBigInteger()) < 0) covered = false;
            }
            if (covered) return true;
            for (int t = 0; t < net.transitionCount(); t++) {
                boolean enabled = true;
                for (int k = 0; k < n && enabled; k++) if (internal(net, k) && marking[k] < net.consumed(t, k)) enabled = false;
                if (!enabled) continue;
                long[] next = marking.clone();
                for (int k = 0; k < n; k++) if (internal(net, k)) next[k] += net.produced(t, k) - net.consumed(t, k);
                if (seen.add(key(next))) queue.add(next);
            }
        }
        return false;
    }

    private static List<Long> key(long[] marking) {
        Long[] boxed = new Long[marking.length];
        for (int i = 0; i < marking.length; i++) boxed[i] = marking[i];
        return Arrays.asList(boxed);
    }

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

    /** Net balance of internal keys, and boundary demand equal to the net deficit, using exact firing counts. */
    private static void assertExactBalance(CycleNet net, CycleSolution result) {
        for (int k = 0; k < net.keyCount(); k++) {
            BigInteger balance = net.stock(k).toBigInteger();
            for (int t = 0; t < net.transitionCount(); t++) {
                balance = balance.add(result.firingsOf(t).toBigInteger()
                    .multiply(BigInteger.valueOf(net.produced(t, k) - net.consumed(t, k))));
            }
            if (internal(net, k)) {
                assertTrue(balance.compareTo(net.required(k).toBigInteger()) >= 0, "key " + k + ": " + result.summary());
            } else if (!result.externalDemand().isEmpty()) {
                BigInteger demand = result.externalDemand().get(k).toBigInteger();
                assertTrue(balance.add(demand).signum() >= 0, "boundary " + k + " under-imported: " + result.summary());
            }
        }
    }
}
