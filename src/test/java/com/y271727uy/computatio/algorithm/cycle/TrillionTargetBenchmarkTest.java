package com.y271727uy.computatio.algorithm.cycle;

import com.y271727uy.computatio.algorithm.amount.PlannerAmount;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Targets of one trillion (10^12) under NeoECO's in-game defaults (10 s, 5M work, 64 MiB): the frozen NeoECO port
 * against the optimised solver on identical inputs. Prints timings and verdicts; asserts nothing beyond running.
 */
@org.junit.jupiter.api.Disabled("Long-running suite, disabled for everyday builds; remove this line to run it")
@org.junit.jupiter.api.Tag("slow")
class TrillionTargetBenchmarkTest {
    private static final long TRILLION = 1_000_000_000_000L;

    private record Run(CycleSolveStatus status, double millis, String why) {}

    private static Run run(CycleSolver solver, CycleNet net) throws InterruptedException {
        long started = System.nanoTime();
        try {
            CycleSolution result = solver.solve(net, CycleSolveLimits.DEFAULT, new PlanningBudget(Cancellation.NONE));
            String why = "";
            for (CycleSolveDiagnostic d : result.diagnostics()) {
                if (d.code() == CycleSolveDiagnostic.Code.MEMORY_BUDGET_EXHAUSTED) why = "MEMORY";
            }
            return new Run(result.status(), (System.nanoTime() - started) / 1e6, why);
        } catch (PlanningBudget.Exhausted exhausted) {
            return new Run(CycleSolveStatus.UNKNOWN_BUDGET, (System.nanoTime() - started) / 1e6,
                exhausted.getMessage().contains("TIME") ? "TIME" : "WORK");
        }
    }

    private static String label(Run r) {
        return (r.status == CycleSolveStatus.SUCCESS ? "OK" : r.status == CycleSolveStatus.INSUFFICIENT_EXTERNAL_INPUT
            ? "SHORT" : "UNKNOWN" + (r.why.isEmpty() ? "" : "/" + r.why)) + String.format(" %.1fms", r.millis);
    }

    @Test
    @Timeout(value = 3600, unit = TimeUnit.SECONDS)
    void trillionTargets() throws Exception {
        CycleSolver original = new ReferenceBoundedCycleSolver();
        CycleSolver optimised = new BoundedCycleSolver();
        // Warm both up so the first shape does not pay JIT compilation.
        for (int i = 0; i < 200; i++) {
            CycleNet warm = CycleSolverEquivalenceTest.randomNet(new Random(i), 4, 3, 6, 9);
            run(original, warm);
            run(optimised, warm);
        }

        Map<String, CycleNet> shapes = new java.util.LinkedHashMap<>();
        shapes.put("container loop (bucket)", container());
        shapes.put("catalyst (press)", catalyst());
        shapes.put("seed doubling", doubling(true));
        shapes.put("3-step growth ring", CycleSolverEquivalenceTest.ring(3, java.math.BigInteger.valueOf(TRILLION), true));
        shapes.put("20-step growth ring", CycleSolverEquivalenceTest.ring(20, java.math.BigInteger.valueOf(TRILLION), true));
        shapes.put("50-step growth ring", CycleSolverEquivalenceTest.ring(50, java.math.BigInteger.valueOf(TRILLION), true));
        shapes.put("byproduct + choice", byproduct());
        shapes.put("3-step ring, search only", searchOnly());
        System.out.println("[1e12] shape                         | NeoECO port          | optimised            | x");
        for (var entry : shapes.entrySet()) {
            Run a = run(original, entry.getValue());
            Run b = run(optimised, entry.getValue());
            System.out.printf("[1e12] %-30s| %-20s | %-20s | %.1f%s%n", entry.getKey(), label(a), label(b),
                a.millis / Math.max(0.001, b.millis), a.status == b.status ? "" : "  (verdict differs)");
        }

        int samples = Integer.parseInt(System.getenv().getOrDefault("COMPUTATIO_1E12_SAMPLES", "200"));
        Random random = new Random(TRILLION);
        List<Double> timesA = new ArrayList<>(), timesB = new ArrayList<>();
        Map<CycleSolveStatus, Integer> verdictA = new EnumMap<>(CycleSolveStatus.class), verdictB = new EnumMap<>(CycleSolveStatus.class);
        Map<String, Integer> unknownA = new TreeMap<>(), unknownB = new TreeMap<>();
        int differ = 0;
        double totalA = 0, totalB = 0;
        for (int i = 0; i < samples; i++) {
            CycleNet small = CycleSolverEquivalenceTest.randomNet(random, 5, 1 + random.nextInt(5), 0, 9);
            PlannerAmount[] required = new PlannerAmount[small.keyCount()];
            for (int k = 0; k < required.length; k++) {
                required[k] = small.required(k).signum() > 0 ? PlannerAmount.of(TRILLION).multiply(small.required(k)) : PlannerAmount.ZERO;
            }
            CycleNet net = small.withRequired(required);
            Run a = run(original, net);
            Run b = run(optimised, net);
            timesA.add(a.millis); timesB.add(b.millis);
            totalA += a.millis; totalB += b.millis;
            verdictA.merge(a.status, 1, Integer::sum); verdictB.merge(b.status, 1, Integer::sum);
            if (a.status == CycleSolveStatus.UNKNOWN_BUDGET) unknownA.merge(a.why, 1, Integer::sum);
            if (b.status == CycleSolveStatus.UNKNOWN_BUDGET) unknownB.merge(b.why, 1, Integer::sum);
            if (a.status != b.status) differ++;
        }
        Collections.sort(timesA);
        Collections.sort(timesB);
        System.out.printf("[1e12] random x%d   NeoECO port: %s unknown-by %s | total %.0f ms p50 %.1f p90 %.1f p99 %.1f max %.1f%n",
            samples, verdictA, unknownA, totalA, pct(timesA, 50), pct(timesA, 90), pct(timesA, 99), timesA.get(samples - 1));
        System.out.printf("[1e12] random x%d   optimised  : %s unknown-by %s | total %.0f ms p50 %.1f p90 %.1f p99 %.1f max %.1f%n",
            samples, verdictB, unknownB, totalB, pct(timesB, 50), pct(timesB, 90), pct(timesB, 99), timesB.get(samples - 1));
        System.out.printf("[1e12] total speed-up x%.1f, verdicts differing %d%n", totalA / Math.max(1, totalB), differ);
    }

    private static double pct(List<Double> sorted, int p) {
        return sorted.get(Math.min(sorted.size() - 1, sorted.size() * p / 100));
    }

    private static CycleNet container() {
        CycleNet.Builder b = CycleNet.builder(4).member(0).member(1).boundary(2).stock(0, 1).required(3, TRILLION);
        b.addTransition("fill", new long[] {1, 0, 1000, 0}, new long[] {0, 1, 0, 0});
        b.addTransition("pour", new long[] {0, 1, 0, 0}, new long[] {1, 0, 0, 1});
        return b.build();
    }

    private static CycleNet catalyst() {
        CycleNet.Builder b = CycleNet.builder(3).member(0).boundary(1).stock(0, 1).required(2, TRILLION);
        b.addTransition("inscribe", new long[] {1, 1, 0}, new long[] {1, 0, 1});
        return b.build();
    }

    private static CycleNet doubling(boolean circuitSafe) {
        CycleNet.Builder b = CycleNet.builder(2).member(0).boundary(1).stock(0, 1).required(0, TRILLION);
        b.addTransition("grow", new long[] {1, 1}, new long[] {2, 0}, circuitSafe, true);
        return b.build();
    }

    private static CycleNet byproduct() {
        CycleNet.Builder b = CycleNet.builder(5).member(0).member(1).boundary(3).boundary(4).required(2, TRILLION);
        b.addTransition("crush", new long[] {0, 0, 0, 1, 0}, new long[] {2, 1, 0, 0, 0});
        b.addTransition("smelt", new long[] {1, 0, 0, 0, 0}, new long[] {0, 0, 1, 0, 0});
        b.addTransition("slag-smelt", new long[] {0, 3, 0, 0, 1}, new long[] {1, 0, 0, 0, 0});
        return b.build();
    }

    private static CycleNet searchOnly() {
        CycleNet.Builder b = CycleNet.builder(3).member(0).member(1).member(2).stock(0, 1).required(0, TRILLION);
        b.addTransition("a", new long[] {1, 0, 0}, new long[] {0, 1, 0}, false, false);
        b.addTransition("b", new long[] {0, 1, 0}, new long[] {0, 0, 1}, false, false);
        b.addTransition("c", new long[] {0, 0, 1}, new long[] {2, 0, 0}, false, false);
        return b.build();
    }
}
