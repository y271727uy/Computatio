package com.y271727uy.computatio.algorithm.cycle;

import static org.junit.jupiter.api.Assertions.*;

import com.y271727uy.computatio.algorithm.amount.PlannerAmount;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The optimised {@link BoundedCycleSolver} must behave exactly like the frozen {@link ReferenceBoundedCycleSolver}:
 * wherever the reference reaches a definite verdict, every observable field must be identical. Where the reference
 * ran out of budget, the optimised solver may finish, but then its answer must be self-consistent.
 */
@org.junit.jupiter.api.Disabled("Long-running suite, disabled for everyday builds; remove this line to run it")
@org.junit.jupiter.api.Tag("slow")
class CycleSolverEquivalenceTest {
    private static final int SAMPLES = Integer.parseInt(System.getenv().getOrDefault("COMPUTATIO_EQUIV_SAMPLES", "4000"));
    private static final long WORK = 1_000_000;

    @Test
    @Timeout(value = 1800, unit = TimeUnit.SECONDS)
    void optimisedSolverMatchesFrozenReference() throws Exception {
        List<CycleNet> corpus = corpus(new Random(0xEC0L));
        CycleSolver reference = new ReferenceBoundedCycleSolver();
        CycleSolver optimised = new BoundedCycleSolver();
        Map<CycleSolveStatus, Integer> referenceOutcomes = new EnumMap<>(CycleSolveStatus.class);
        int improved = 0;
        long referenceNanos = 0;
        long optimisedNanos = 0;
        for (int i = 0; i < corpus.size(); i++) {
            CycleNet net = corpus.get(i);
            long a = System.nanoTime();
            CycleSolution expected = run(reference, net);
            long b = System.nanoTime();
            CycleSolution actual = run(optimised, net);
            long c = System.nanoTime();
            referenceNanos += b - a;
            optimisedNanos += c - b;
            referenceOutcomes.merge(expected.status(), 1, Integer::sum);
            String context = "net " + i + ": reference " + expected.summary() + " / optimised " + actual.summary();
            if (expected.status() == CycleSolveStatus.SUCCESS || expected.status() == CycleSolveStatus.INSUFFICIENT_EXTERNAL_INPUT) {
                assertEquals(fingerprint(expected), fingerprint(actual), context);
            } else if (actual.status() != expected.status()) {
                assertNotEquals(CycleSolveStatus.INSUFFICIENT_EXTERNAL_INPUT, actual.status(),
                    "an unproved infeasibility appeared where the reference was unknown: " + context);
                if (actual.status() == CycleSolveStatus.SUCCESS) assertBalanced(net, actual, context);
                improved++;
            }
        }
        System.out.printf("[Equivalence] %d nets, reference outcomes %s, unknown->decided %d; reference %.0f ms, optimised %.0f ms (x%.2f)%n",
            corpus.size(), referenceOutcomes, improved, referenceNanos / 1e6, optimisedNanos / 1e6,
            (double) referenceNanos / Math.max(1, optimisedNanos));
    }

    static CycleSolution run(CycleSolver solver, CycleNet net) throws InterruptedException {
        try {
            return solver.solve(net, CycleSolveLimits.DEFAULT, new PlanningBudget(Cancellation.NONE, WORK, Long.MAX_VALUE, () -> 0L));
        } catch (PlanningBudget.Exhausted exhausted) {
            return CycleSolution.failure(CycleSolveStatus.UNKNOWN_BUDGET, CycleSolveDiagnostic.Code.STATE_EQUATION_BUDGET, "work");
        }
    }

    static String fingerprint(CycleSolution s) {
        List<CycleSolveDiagnostic.Code> codes = new ArrayList<>();
        for (CycleSolveDiagnostic d : s.diagnostics()) if (d.code() != CycleSolveDiagnostic.Code.SEARCH_METRICS) codes.add(d.code());
        return s.status() + "|" + s.countKnowledge() + "|firings=" + s.firings() + "|plan=" + s.plan() + "|demand=" + s.externalDemand()
            + "|seed=" + s.requiredSeed() + "|short=" + s.seedShortfall() + "|produced=" + s.produced() + "|deliver="
            + s.deliverable() + "|startup=" + s.startupCandidates() + "|codes=" + codes;
    }

    private static void assertBalanced(CycleNet net, CycleSolution s, String context) {
        for (int k = 0; k < net.keyCount(); k++) {
            if (net.boundary(k) && !net.member(k)) continue;
            BigInteger balance = net.stock(k).toBigInteger();
            for (int t = 0; t < net.transitionCount(); t++) {
                balance = balance.add(s.firingsOf(t).toBigInteger().multiply(BigInteger.valueOf(net.produced(t, k) - net.consumed(t, k))));
            }
            assertTrue(balance.compareTo(net.required(k).toBigInteger()) >= 0, "key " + k + " " + context);
        }
    }

    /** Mix of fuel-bounded nets, free nets, quadrillion-scaled nets and structured rings. */
    static List<CycleNet> corpus(Random random) {
        List<CycleNet> nets = new ArrayList<>();
        for (int i = 0; i < SAMPLES; i++) {
            switch (i % 4) {
                case 0: nets.add(randomNet(random, 3 + random.nextInt(2), 1 + random.nextInt(5), 6, 9)); break;
                case 1: nets.add(randomNet(random, 5, 1 + random.nextInt(5), 0, 40)); break;
                case 2: {
                    CycleNet small = randomNet(random, 5, 1 + random.nextInt(5), 0, 9);
                    PlannerAmount[] required = new PlannerAmount[small.keyCount()];
                    BigInteger scale = BigInteger.TEN.pow(6 + random.nextInt(12));
                    for (int k = 0; k < required.length; k++) required[k] = PlannerAmount.of(small.required(k).toBigInteger().multiply(scale));
                    nets.add(small.withRequired(required));
                    break;
                }
                default: nets.add(ring(2 + random.nextInt(30), BigInteger.TEN.pow(1 + random.nextInt(30)), random.nextBoolean()));
            }
        }
        return nets;
    }

    static CycleNet randomNet(Random random, int materials, int transitions, int fuel, int maxTarget) {
        boolean fuelled = fuel > 0;
        int fuelKey = materials;
        int importKey = materials + (fuelled ? 1 : 0);
        int keys = importKey + 1;
        CycleNet.Builder builder = CycleNet.builder(keys).boundary(importKey);
        if (fuelled) builder.stock(fuelKey, random.nextInt(fuel + 1));
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

    static CycleNet ring(int size, BigInteger target, boolean imports) {
        int keys = size + 1;
        CycleNet.Builder b = CycleNet.builder(keys).boundary(size).stock(0, 1).required(0, PlannerAmount.of(target));
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
