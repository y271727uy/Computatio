package com.y271727uy.computatio.api.math;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigInteger;
import java.util.Random;
import org.junit.jupiter.api.Test;

class SafeMathTest {
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

    @Test
    void saturatingOperationsMatchClampedExactArithmetic() {
        Random random = new Random(7);
        long[] edges = {Long.MIN_VALUE, -1, 0, 1, 2, 3_037_000_499L, 3_037_000_500L, Long.MAX_VALUE / 2,
            Long.MAX_VALUE - 1, Long.MAX_VALUE};
        for (int i = 0; i < 100_000; i++) {
            long a = i < 100 ? edges[i % edges.length] : random.nextLong() >> random.nextInt(64);
            long b = i < 100 ? edges[(i / edges.length) % edges.length] : random.nextLong() >> random.nextInt(64);
            BigInteger x = BigInteger.valueOf(Math.max(0L, a));
            BigInteger y = BigInteger.valueOf(Math.max(0L, b));
            assertEquals(x.add(y).min(LONG_MAX).longValue(), SafeMath.saturatingAdd(a, b));
            assertEquals(x.multiply(y).min(LONG_MAX).longValue(), SafeMath.saturatingMultiply(a, b));
            assertEquals(x.subtract(y).max(BigInteger.ZERO).longValue(), SafeMath.subtractFloorZero(a, b));
        }
    }

    @Test
    void bucketedCapacityStaysExactAtTheEdges() {
        assertEquals(Long.MAX_VALUE, SafeMath.remainingBucketedAmount(Long.MAX_VALUE, 0, 0, 8));
        assertEquals(0L, SafeMath.remainingBucketedAmount(10, 11, 0, 8));
        assertEquals(BigInteger.ONE.add(BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.valueOf(Long.MAX_VALUE))),
            SafeMath.usedBucketedBytes(Long.MAX_VALUE, BigInteger.ONE, 1, Long.MAX_VALUE));
        assertEquals(BigInteger.ONE.shiftLeft(1000).add(BigInteger.ONE),
            SafeMath.usedBucketedBytes(0, BigInteger.ONE.shiftLeft(1000).add(BigInteger.ONE), 1, 0));
    }
}
