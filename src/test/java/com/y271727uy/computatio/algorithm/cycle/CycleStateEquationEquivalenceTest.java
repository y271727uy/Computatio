package com.y271727uy.computatio.algorithm.cycle;

import static org.junit.jupiter.api.Assertions.*;

import com.y271727uy.computatio.algorithm.amount.PlannerAmount;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** The long-fast-path simplex must return exactly what the frozen all-BigInteger simplex returns. */
class CycleStateEquationEquivalenceTest {
    private static final long[] EDGE = {1, 2, 3, 7, 64, 1000, 3_037_000_499L, 3_037_000_500L, 1L << 31, (1L << 32) + 1,
        Long.MAX_VALUE / 3, Long.MAX_VALUE / 2, Long.MAX_VALUE - 1, Long.MAX_VALUE};

    @Test
    void randomMaterialBalancesMatchTheReference() throws Exception {
        Random random = new Random(0x51A1L);
        int samples = Integer.parseInt(System.getenv().getOrDefault("COMPUTATIO_LP_SAMPLES", "20000"));
        int compared = 0;
        for (int sample = 0; sample < samples; sample++) {
            int keys = 1 + random.nextInt(5);
            int transitions = 1 + random.nextInt(5);
            int scale = sample % 3; // 0 small, 1 mixed, 2 long-edge coefficients
            long[][] consumed = new long[transitions][keys];
            long[][] produced = new long[transitions][keys];
            for (int t = 0; t < transitions; t++) {
                for (int k = 0; k < keys; k++) {
                    if (random.nextInt(2) == 0) consumed[t][k] = coefficient(random, scale);
                    if (random.nextInt(2) == 0) produced[t][k] = coefficient(random, scale);
                }
            }
            boolean[] boundary = new boolean[keys];
            PlannerAmount[] stock = new PlannerAmount[keys];
            PlannerAmount[] target = new PlannerAmount[keys];
            for (int k = 0; k < keys; k++) {
                boundary[k] = random.nextInt(4) == 0;
                stock[k] = amount(random, scale);
                target[k] = random.nextBoolean() ? amount(random, scale) : PlannerAmount.ZERO;
            }
            ReferenceCycleStateEquation.Result expected;
            try {
                expected = ReferenceCycleStateEquation.solve(consumed, produced, boundary, stock, target,
                    new PlanningBudget(Cancellation.NONE, 200_000, Long.MAX_VALUE, () -> 0L));
            } catch (PlanningBudget.Exhausted exhausted) {
                assertThrows(PlanningBudget.Exhausted.class, () -> CycleStateEquation.solve(consumed, produced, boundary,
                    stock, target, new PlanningBudget(Cancellation.NONE, 200_000, Long.MAX_VALUE, () -> 0L)));
                continue;
            }
            CycleStateEquation.Result actual = CycleStateEquation.solve(consumed, produced, boundary, stock, target,
                new PlanningBudget(Cancellation.NONE, 200_000, Long.MAX_VALUE, () -> 0L));
            String context = "sample " + sample + " cons=" + Arrays.deepToString(consumed) + " prod="
                + Arrays.deepToString(produced) + " stock=" + Arrays.toString(stock) + " target=" + Arrays.toString(target);
            assertEquals(expected.status().name(), actual.status().name(), context);
            assertEquals(expected.counts() == null ? null : Arrays.asList(expected.counts()),
                actual.counts() == null ? null : Arrays.asList(actual.counts()), context);
            compared++;
        }
        System.out.println("[LP equivalence] compared " + compared + " of " + samples);
    }

    private static long coefficient(Random random, int scale) {
        switch (scale) {
            case 0: return random.nextInt(5);
            case 1: return random.nextBoolean() ? random.nextInt(65) : 1 + (random.nextLong() >>> (1 + random.nextInt(40)));
            default: return random.nextInt(3) == 0 ? random.nextInt(4) : EDGE[random.nextInt(EDGE.length)];
        }
    }

    private static PlannerAmount amount(Random random, int scale) {
        switch (scale) {
            case 0: return PlannerAmount.of(random.nextInt(20));
            case 1: return PlannerAmount.of(random.nextLong() >>> (1 + random.nextInt(62)));
            default: return random.nextBoolean() ? PlannerAmount.of(EDGE[random.nextInt(EDGE.length)])
                : PlannerAmount.of(new BigInteger(64 + random.nextInt(100), random));
        }
    }
}
