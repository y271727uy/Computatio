package com.y271727uy.computatio.algorithm.boundedresource;

/** Saturating arithmetic for non-negative resource quantities. */
public final class SaturatingArithmetic {
    private SaturatingArithmetic() {}

    public static long add(long left, long right) {
        left = Math.max(0L, left);
        right = Math.max(0L, right);
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    public static long multiply(long left, long right) {
        left = Math.max(0L, left);
        right = Math.max(0L, right);
        if (left == 0L || right == 0L) return 0L;
        return left > Long.MAX_VALUE / right ? Long.MAX_VALUE : left * right;
    }

    public static int addInt(int left, int right) {
        left = Math.max(0, left);
        right = Math.max(0, right);
        return left > Integer.MAX_VALUE - right ? Integer.MAX_VALUE : left + right;
    }

    /** Clamps to {@code [0, Long.MAX_VALUE]}; {@code null} and negative values become zero. */
    public static long clampToLong(java.math.BigInteger value) {
        if (value == null || value.signum() < 0) return 0L;
        return value.bitLength() > 63 ? Long.MAX_VALUE : value.longValue();
    }

    public static long subtractFloorZero(long left, long right) {
        return Math.max(0L, left) <= Math.max(0L, right)
                ? 0L : Math.max(0L, left) - Math.max(0L, right);
    }
}
