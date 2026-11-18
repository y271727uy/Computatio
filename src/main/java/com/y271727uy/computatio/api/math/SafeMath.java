package com.y271727uy.computatio.api.math;

import com.y271727uy.computatio.algorithm.amount.PlannerAmount;
import com.y271727uy.computatio.algorithm.boundedresource.BucketedCapacityAccounting;
import com.y271727uy.computatio.algorithm.boundedresource.SaturatingArithmetic;
import com.y271727uy.computatio.algorithm.math.BigIntegers;

import java.math.BigInteger;

/**
 * Overflow-safe arithmetic for non-negative quantities. Saturating operations treat negative inputs as zero and
 * stop at the type's maximum instead of wrapping. Use {@link PlannerAmount} when the exact value matters.
 */
public final class SafeMath {
    private SafeMath() {
    }

    public static long saturatingAdd(long left, long right) {
        return SaturatingArithmetic.add(left, right);
    }

    public static int saturatingAddInt(int left, int right) {
        return SaturatingArithmetic.addInt(left, right);
    }

    public static long saturatingMultiply(long left, long right) {
        return SaturatingArithmetic.multiply(left, right);
    }

    public static long subtractFloorZero(long left, long right) {
        return SaturatingArithmetic.subtractFloorZero(left, right);
    }

    /** Clamps to {@code [0, Long.MAX_VALUE]}; {@code null} and negative values become zero. */
    public static long clampToLong(BigInteger value) {
        return SaturatingArithmetic.clampToLong(value);
    }

    /** {@code null} and negative values become zero. */
    public static BigInteger nonNegative(BigInteger value) {
        return BigIntegers.nonNegative(value);
    }

    /** Exact value as a {@link PlannerAmount}, promoting to {@link BigInteger} only when needed. */
    public static PlannerAmount exact(long value) {
        return PlannerAmount.of(value);
    }

    /** Remaining amount that fits in fixed-size buckets, saturating at {@code Long.MAX_VALUE}. */
    public static long remainingBucketedAmount(long totalBytes, long usedBytes, long currentAmount, long amountPerByte) {
        return BucketedCapacityAccounting.remainingAmount(totalBytes, usedBytes, currentAmount, amountPerByte);
    }

    /** Exact bytes used by {@code storedTypes} types holding {@code storedAmount} units in fixed-size buckets. */
    public static BigInteger usedBucketedBytes(long storedTypes, BigInteger storedAmount, long amountPerByte,
            long bytesPerType) {
        return BucketedCapacityAccounting.usedBytes(storedTypes, storedAmount, amountPerByte, bytesPerType);
    }
}
