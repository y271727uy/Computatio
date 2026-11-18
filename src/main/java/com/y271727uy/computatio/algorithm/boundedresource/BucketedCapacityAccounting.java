package com.y271727uy.computatio.algorithm.boundedresource;

import java.math.BigInteger;

/** Capacity arithmetic for values stored in fixed-size buckets. */
public final class BucketedCapacityAccounting {
    private BucketedCapacityAccounting() {}

    public static long remainingAmount(long totalBytes, long usedBytes,
                                       long currentAmount, long amountPerByte) {
        if (totalBytes <= 0L || usedBytes < 0L || usedBytes > totalBytes || currentAmount < 0L) {
            return 0L;
        }
        amountPerByte = Math.max(1L, amountPerByte);
        long completion = currentAmount <= 0L
                ? 0L
                : (amountPerByte - currentAmount % amountPerByte) % amountPerByte;
        return SaturatingArithmetic.add(completion,
                SaturatingArithmetic.multiply(totalBytes - usedBytes, amountPerByte));
    }

    public static BigInteger usedBytes(long storedTypes, BigInteger storedAmount,
                                       long amountPerByte, long bytesPerType) {
        amountPerByte = Math.max(1L, amountPerByte);
        BigInteger amount = storedAmount == null || storedAmount.signum() <= 0
                ? BigInteger.ZERO : storedAmount;
        BigInteger[] division = amount.divideAndRemainder(BigInteger.valueOf(amountPerByte));
        BigInteger contentBytes = division[0].add(
                division[1].signum() == 0 ? BigInteger.ZERO : BigInteger.ONE);
        return contentBytes.add(BigInteger.valueOf(Math.max(0L, storedTypes))
                .multiply(BigInteger.valueOf(Math.max(0L, bytesPerType))));
    }
}
