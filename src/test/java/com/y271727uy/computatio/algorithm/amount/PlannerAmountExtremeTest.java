package com.y271727uy.computatio.algorithm.amount;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.Random;
import org.junit.jupiter.api.Test;

class PlannerAmountExtremeTest {
    private static final long[] EDGES = {
        Long.MIN_VALUE, Long.MIN_VALUE + 1, -(1L << 32), -2, -1, 0, 1, 2, (1L << 31) - 1, 1L << 31, 1L << 32,
        3_037_000_499L, 3_037_000_500L, Long.MAX_VALUE / 2, Long.MAX_VALUE - 1, Long.MAX_VALUE
    };

    @Test
    void everyEdgePairAgreesWithBigInteger() {
        for (long a : EDGES) {
            for (long b : EDGES) {
                check(BigInteger.valueOf(a), BigInteger.valueOf(b));
            }
        }
    }

    @Test
    void randomMixedWidthOperationsAgreeWithBigInteger() {
        Random random = new Random(0xC0FFEE);
        for (int i = 0; i < 200_000; i++) {
            check(sample(random), sample(random));
        }
    }

    @Test
    void promotionAndDemotionAreValueBased() {
        PlannerAmount max = PlannerAmount.of(Long.MAX_VALUE);
        PlannerAmount wide = max.add(PlannerAmount.ONE);
        assertFalse(wide.fitsLong());
        PlannerAmount back = wide.subtract(PlannerAmount.ONE);
        assertTrue(back.fitsLong());
        assertEquals(max, back);
        assertEquals(max.hashCode(), back.hashCode());
        assertEquals(PlannerAmount.of(BigInteger.valueOf(Long.MAX_VALUE)), max);
        assertThrows(ArithmeticException.class, wide::longValueExact);
        assertEquals(BigInteger.valueOf(Long.MIN_VALUE).negate(),
            PlannerAmount.of(Long.MIN_VALUE).divide(PlannerAmount.of(-1)).toBigInteger());
        assertEquals(PlannerAmount.ZERO, PlannerAmount.of(Long.MIN_VALUE).remainder(PlannerAmount.of(-1)));
    }

    @Test
    void hugeExactValuesKeepEveryDigit() {
        BigInteger googolPlusOne = BigInteger.TEN.pow(100).add(BigInteger.ONE);
        PlannerAmount amount = PlannerAmount.of(googolPlusOne);
        assertEquals(googolPlusOne.multiply(googolPlusOne), amount.multiply(amount).toBigInteger());
        assertEquals(BigInteger.ONE, amount.subtract(PlannerAmount.of(BigInteger.TEN.pow(100))).toBigInteger());
        BigInteger huge = BigInteger.ONE.shiftLeft(100_000).add(BigInteger.ONE);
        assertEquals(BigInteger.ONE.shiftLeft(99_999).add(BigInteger.ONE),
            PlannerAmount.of(huge).ceilDiv(PlannerAmount.of(2)).toBigInteger());
    }

    @Test
    void invalidDivisionIsRejected() {
        assertThrows(ArithmeticException.class, () -> PlannerAmount.ONE.divide(PlannerAmount.ZERO));
        assertThrows(ArithmeticException.class, () -> PlannerAmount.ONE.remainder(PlannerAmount.ZERO));
        assertThrows(IllegalArgumentException.class, () -> PlannerAmount.of(-1).ceilDiv(PlannerAmount.ONE));
        assertThrows(IllegalArgumentException.class, () -> PlannerAmount.ONE.ceilDiv(PlannerAmount.ZERO));
    }

    private static void check(BigInteger a, BigInteger b) {
        PlannerAmount x = PlannerAmount.of(a);
        PlannerAmount y = PlannerAmount.of(b);
        String context = a + " , " + b;
        assertEquals(a.add(b), x.add(y).toBigInteger(), context);
        assertEquals(a.subtract(b), x.subtract(y).toBigInteger(), context);
        assertEquals(a.multiply(b), x.multiply(y).toBigInteger(), context);
        assertEquals(Integer.signum(a.compareTo(b)), Integer.signum(x.compareTo(y)), context);
        assertEquals(a.equals(b), x.equals(y), context);
        assertEquals(a.hashCode(), x.hashCode(), context);
        assertEquals(b.hashCode(), y.hashCode(), context);
        assertEquals(a.min(b), x.min(y).toBigInteger(), context);
        assertEquals(a.max(b), x.max(y).toBigInteger(), context);
        assertEquals(a.signum(), x.signum(), context);
        assertEquals(a.bitLength() <= 63, x.fitsLong(), context);
        if (b.signum() != 0) {
            assertEquals(a.divide(b), x.divide(y).toBigInteger(), context);
            assertEquals(a.remainder(b), x.remainder(y).toBigInteger(), context);
        }
        if (a.signum() >= 0 && b.signum() > 0) {
            BigInteger[] qr = a.divideAndRemainder(b);
            BigInteger ceil = qr[1].signum() == 0 ? qr[0] : qr[0].add(BigInteger.ONE);
            assertEquals(ceil, x.ceilDiv(y).toBigInteger(), context);
        }
    }

    private static BigInteger sample(Random random) {
        switch (random.nextInt(5)) {
            case 0: return BigInteger.valueOf(EDGES[random.nextInt(EDGES.length)] + random.nextInt(5) - 2);
            case 1: return BigInteger.valueOf(random.nextLong());
            case 2: return BigInteger.valueOf(random.nextInt(2001) - 1000);
            case 3: return new BigInteger(64 + random.nextInt(200), random).multiply(BigInteger.valueOf(random.nextBoolean() ? 1 : -1));
            default: return BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.valueOf(random.nextInt(5) - 2));
        }
    }
}
