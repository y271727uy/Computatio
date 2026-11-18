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
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.function.LongFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Can a cyclic recipe with a 10^15 ("千万亿") target be planned inside the MC default budget (10 s, 5M work)? */
@org.junit.jupiter.api.Disabled("Long-running suite, disabled for everyday builds; remove this line to run it")
@org.junit.jupiter.api.Tag("slow")
class CycleSolvingQuadrillionTest {
    private static final long QUADRILLION = 1_000_000_000_000_000L;

    private record Outcome(CycleSolveStatus status, double millis, String note) {}

    private static Outcome run(CycleNet net) throws InterruptedException {
        long started = System.nanoTime();
        try {
            CycleSolution result = CycleSolving.solve(net, CycleSolveLimits.DEFAULT, new PlanningBudget(Cancellation.NONE));
            double millis = (System.nanoTime() - started) / 1e6;
            if (result.status() == CycleSolveStatus.SUCCESS) {
                for (int k = 0; k < net.keyCount(); k++) {
                    if (net.boundary(k) && !net.member(k)) continue;
                    BigInteger balance = net.stock(k).toBigInteger();
                    for (int t = 0; t < net.transitionCount(); t++) {
                        balance = balance.add(result.firingsOf(t).toBigInteger()
                            .multiply(BigInteger.valueOf(net.produced(t, k) - net.consumed(t, k))));
                    }
                    assertTrue(balance.compareTo(net.required(k).toBigInteger()) >= 0, "unbalanced key " + k);
                }
            }
            String via = result.diagnostics().isEmpty() ? "" : String.valueOf(result.diagnostics().get(result.diagnostics().size() - 1).code());
            return new Outcome(result.status(), millis, via);
        } catch (PlanningBudget.Exhausted exhausted) {
            return new Outcome(CycleSolveStatus.UNKNOWN_BUDGET, (System.nanoTime() - started) / 1e6,
                exhausted.getMessage().contains("TIME") ? "TIME_LIMIT" : "WORK_LIMIT");
        }
    }

    private static void report(String name, LongFunction<CycleNet> shape) throws InterruptedException {
        StringBuilder line = new StringBuilder(String.format("[1e15] %-34s", name));
        for (long target : new long[] {1_000_000L, 1_000_000_000_000L, QUADRILLION, Long.MAX_VALUE}) {
            Outcome o = run(shape.apply(target));
            line.append(String.format(" | %s %s %.1fms %s", target == Long.MAX_VALUE ? "LMAX" : "1e" + Long.toString(target).length() / 1 ,
                o.status() == CycleSolveStatus.SUCCESS ? "OK" : o.status().name(), o.millis(), o.note()));
        }
        System.out.println(line.toString().replace("1e7 ", "1e6 ").replace("1e13 ", "1e12 ").replace("1e16 ", "1e15 "));
    }

    @Test
    @Timeout(value = 900, unit = TimeUnit.SECONDS)
    void typicalAe2CycleShapesAtAQuadrillion() throws Exception {
        // 1. Container loop: bucket -> water bucket -> concrete + bucket.
        report("container loop (bucket)", target -> {
            CycleNet.Builder b = CycleNet.builder(4).member(0).member(1).boundary(2).stock(0, 1).required(3, target);
            b.addTransition("fill", new long[] {1, 0, 1000, 0}, new long[] {0, 1, 0, 0});
            b.addTransition("pour", new long[] {0, 1, 0, 0}, new long[] {1, 0, 0, 1});
            return b.build();
        });
        // 2. Catalyst returned (inscriber press): press + silicon -> press + circuit.
        report("catalyst (press)", target -> {
            CycleNet.Builder b = CycleNet.builder(3).member(0).boundary(1).stock(0, 1).required(2, target);
            b.addTransition("inscribe", new long[] {1, 1, 0}, new long[] {1, 0, 1});
            return b.build();
        });
        // 3. Seed growth: 1 seed + dust -> 2 seeds (single pattern net growth).
        report("seed doubling (1 pattern)", target -> {
            CycleNet.Builder b = CycleNet.builder(2).member(0).boundary(1).stock(0, 1).required(0, target);
            b.addTransition("grow", new long[] {1, 1}, new long[] {2, 0});
            return b.build();
        });
        // 4. Growth through a 3-step ring with imports.
        report("3-step growth ring + imports", target -> ring(3, target, true));
        // 5. 20-step ring.
        report("20-step growth ring + imports", target -> ring(20, target, true));
        // 6. Byproduct loop: ore -> dust + 10% byproduct, byproduct + dust -> 2 ingot ... with exchange.
        report("byproduct + choice", target -> {
            // keys: 0 dust(M), 1 slag(M), 2 ingot(target), 3 ore(B), 4 flux(B)
            CycleNet.Builder b = CycleNet.builder(5).member(0).member(1).boundary(3).boundary(4).required(2, target);
            b.addTransition("crush", new long[] {0, 0, 0, 1, 0}, new long[] {2, 1, 0, 0, 0});
            b.addTransition("smelt", new long[] {1, 0, 0, 0, 0}, new long[] {0, 0, 1, 0, 0});
            b.addTransition("slag-smelt", new long[] {0, 3, 0, 0, 1}, new long[] {1, 0, 0, 0, 0});
            return b.build();
        });
        // 7. Same 3-step ring, but every pattern forbids circuit algebra (search only).
        report("3-step ring, search only", target -> {
            CycleNet.Builder b = CycleNet.builder(3).member(0).member(1).member(2).stock(0, 1).required(0, target);
            b.addTransition("a", new long[] {1, 0, 0}, new long[] {0, 1, 0}, false, false);
            b.addTransition("b", new long[] {0, 1, 0}, new long[] {0, 0, 1}, false, false);
            b.addTransition("c", new long[] {0, 0, 1}, new long[] {2, 0, 0}, false, false);
            return b.build();
        });
    }

    @Test
    @Timeout(value = 1800, unit = TimeUnit.SECONDS)
    void randomCyclesScaledToAQuadrillion() throws Exception {
        Random random = new Random(1_000_000_000_000_000L);
        Map<CycleSolveStatus, Integer> outcomes = new EnumMap<>(CycleSolveStatus.class);
        List<Double> times = new ArrayList<>();
        int samples = Integer.parseInt(System.getenv().getOrDefault("COMPUTATIO_1E15_SAMPLES", "200"));
        Map<String, Integer> slowReasons = new java.util.TreeMap<>();
        for (int i = 0; i < samples; i++) {
            CycleNet small = CycleSolvingStressTest.randomNet(random, 5, 1 + random.nextInt(5), 0, 9);
            PlannerAmount[] required = new PlannerAmount[small.keyCount()];
            for (int k = 0; k < small.keyCount(); k++) {
                required[k] = small.required(k).signum() > 0 ? PlannerAmount.of(QUADRILLION).multiply(small.required(k)) : PlannerAmount.ZERO;
            }
            Outcome o = run(small.withRequired(required));
            outcomes.merge(o.status(), 1, Integer::sum);
            times.add(o.millis());
            if (o.status() == CycleSolveStatus.UNKNOWN_BUDGET) slowReasons.merge(o.note(), 1, Integer::sum);
        }
        Collections.sort(times);
        System.out.printf("[1e15] random cycles x%d: %s; p50 %.1f ms, p90 %.1f ms, p99 %.1f ms, max %.1f ms; unknown by %s%n",
            samples, outcomes, times.get(samples / 2), times.get(samples * 9 / 10), times.get(samples * 99 / 100),
            times.get(samples - 1), slowReasons);
    }

    private static CycleNet ring(int size, long target, boolean imports) {
        int keys = size + 1;
        CycleNet.Builder b = CycleNet.builder(keys).boundary(size).stock(0, 1).required(0, target);
        for (int k = 0; k < size; k++) b.member(k);
        for (int t = 0; t < size; t++) {
            long[] in = new long[keys];
            long[] out = new long[keys];
            in[t] = 1;
            if (imports) in[size] = 1 + t % 3;
            out[(t + 1) % size] = t == size - 1 ? 2 : 1;
            b.addTransition("r" + t, in, out);
        }
        return b.build();
    }
}
