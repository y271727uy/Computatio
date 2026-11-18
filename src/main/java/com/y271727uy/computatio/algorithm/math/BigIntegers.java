package com.y271727uy.computatio.algorithm.math;

import java.math.BigInteger;

public final class BigIntegers {
    private BigIntegers() {
    }

    public static BigInteger nonNegative(BigInteger value) {
        if (value == null || value.signum() < 0) {
            return BigInteger.ZERO;
        }
        return value;
    }
}
