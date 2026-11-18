package com.y271727uy.computatio.algorithm.boundedresource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;

class BoundedResourceTest {
    @Test
    void saturatingArithmeticClampsOverflowAndNegativeInputs() {
        assertEquals(Long.MAX_VALUE, SaturatingArithmetic.add(Long.MAX_VALUE, 1L));
        assertEquals(Long.MAX_VALUE, SaturatingArithmetic.multiply(Long.MAX_VALUE, 2L));
        assertEquals(2L, SaturatingArithmetic.add(-5L, 2L));
        assertEquals(3L, SaturatingArithmetic.subtractFloorZero(7L, 4L));
        assertEquals(0L, SaturatingArithmetic.subtractFloorZero(4L, 7L));
    }

    @Test
    void capacityAccountingRoundsBucketsAndSupportsBigAmounts() {
        assertEquals(11L, BucketedCapacityAccounting.remainingAmount(3L, 1L, 5L, 4L));
        assertEquals(BigInteger.valueOf(9L), BucketedCapacityAccounting.usedBytes(
                2L, BigInteger.valueOf(17L), 8L, 3L));
        assertEquals(BigInteger.ZERO, BucketedCapacityAccounting.usedBytes(
                -1L, BigInteger.valueOf(-10L), 8L, 3L));
    }

    @Test
    void findsLargestFeasibleAmountIncludingLongMaximum() {
        assertEquals(17L, MaxFeasibleSearch.find(100L, value -> value <= 17L));
        assertEquals(Long.MAX_VALUE, MaxFeasibleSearch.find(Long.MAX_VALUE, value -> true));
        assertEquals(0L, MaxFeasibleSearch.find(100L, value -> false));
        assertThrows(IllegalArgumentException.class, () -> MaxFeasibleSearch.find(-1L, value -> true));
    }

    @Test
    void evictsLeastRecentlyUsedEntry() {
        BoundedLruCache<Integer, String> cache = new BoundedLruCache<>(2);
        cache.put(1, "one");
        cache.put(2, "two");
        assertEquals("one", cache.get(1));
        cache.put(3, "three");
        assertTrue(cache.containsKey(1));
        assertFalse(cache.containsKey(2));
        assertEquals(2, cache.size());
    }
}
