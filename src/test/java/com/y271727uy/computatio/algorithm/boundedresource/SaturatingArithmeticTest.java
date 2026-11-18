package com.y271727uy.computatio.algorithm.boundedresource;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;

class SaturatingArithmeticTest {
    @Test
    void intAddAddsInRange() {
        assertEquals(7, SaturatingArithmetic.addInt(3, 4));
        assertEquals(0, SaturatingArithmetic.addInt(0, 0));
    }

    @Test
    void intAddStopsAtMaxValue() {
        assertEquals(Integer.MAX_VALUE, SaturatingArithmetic.addInt(Integer.MAX_VALUE, 1));
        assertEquals(Integer.MAX_VALUE, SaturatingArithmetic.addInt(Integer.MAX_VALUE - 1, 2));
        assertEquals(Integer.MAX_VALUE, SaturatingArithmetic.addInt(Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertEquals(Integer.MAX_VALUE - 1, SaturatingArithmetic.addInt(Integer.MAX_VALUE - 2, 1));
    }

    @Test
    void intAddClampsNegativeInputsToZero() {
        assertEquals(4, SaturatingArithmetic.addInt(-1, 4));
        assertEquals(3, SaturatingArithmetic.addInt(3, -9));
        assertEquals(0, SaturatingArithmetic.addInt(-7, -9));
        assertEquals(Integer.MAX_VALUE, SaturatingArithmetic.addInt(Integer.MIN_VALUE, Integer.MAX_VALUE));
    }

    @Test
    void clampToLongHandlesNullNegativeRepresentableAndWideValues() {
        assertEquals(0L, SaturatingArithmetic.clampToLong(null));
        assertEquals(0L, SaturatingArithmetic.clampToLong(BigInteger.valueOf(-1L)));
        assertEquals(0L, SaturatingArithmetic.clampToLong(BigInteger.valueOf(Long.MIN_VALUE).subtract(BigInteger.ONE)));
        assertEquals(0L, SaturatingArithmetic.clampToLong(BigInteger.ZERO));
        assertEquals(123L, SaturatingArithmetic.clampToLong(BigInteger.valueOf(123L)));
        assertEquals(Long.MAX_VALUE, SaturatingArithmetic.clampToLong(BigInteger.valueOf(Long.MAX_VALUE)));
        assertEquals(Long.MAX_VALUE, SaturatingArithmetic.clampToLong(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE)));
        assertEquals(Long.MAX_VALUE, SaturatingArithmetic.clampToLong(BigInteger.ONE.shiftLeft(200)));
    }
}
