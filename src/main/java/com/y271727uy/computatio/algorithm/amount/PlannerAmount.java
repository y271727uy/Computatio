package com.y271727uy.computatio.algorithm.amount;

import java.math.BigInteger;
import java.util.Objects;

/**
 * Exact non-allocating-for-the-common-case arithmetic used by the planner.
 *
 * <p>Values that fit in a signed {@code long} stay on the fast path. The first
 * overflowing operation promotes the value to {@link BigInteger}; subsequent
 * operations on that value remain exact. The type is signed because subtraction
 * is useful while calculating deltas, although ordinary material quantities are
 * non-negative.
 */
public final class PlannerAmount implements Comparable<PlannerAmount> {
    public static final PlannerAmount ZERO = new PlannerAmount(0L);
    public static final PlannerAmount ONE = new PlannerAmount(1L);

    private static final BigInteger LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE);
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

    private final long fastValue;
    private final BigInteger wideValue;

    private PlannerAmount(long value) {
        this.fastValue = value;
        this.wideValue = null;
    }

    private PlannerAmount(BigInteger value) {
        Objects.requireNonNull(value, "value");
        if (fitsLong(value)) {
            this.fastValue = value.longValue();
            this.wideValue = null;
        } else {
            this.fastValue = 0L;
            this.wideValue = value;
        }
    }

    private static final int CACHE_LOW = -128;
    private static final int CACHE_HIGH = 1024;
    private static final PlannerAmount[] CACHE = new PlannerAmount[CACHE_HIGH - CACHE_LOW + 1];

    public static PlannerAmount of(long value) {
        if (value == 0L) return ZERO;
        if (value == 1L) return ONE;
        // Immutable and compared by value, so small counts are shared like Long.valueOf (planner loops allocate them hot).
        if (value >= CACHE_LOW && value <= CACHE_HIGH) {
            int slot = (int) value - CACHE_LOW;
            PlannerAmount cached = CACHE[slot];
            if (cached == null) CACHE[slot] = cached = new PlannerAmount(value);
            return cached;
        }
        return new PlannerAmount(value);
    }

    public static PlannerAmount of(BigInteger value) {
        Objects.requireNonNull(value, "value");
        if (value.signum() == 0) return ZERO;
        if (value.equals(BigInteger.ONE)) return ONE;
        return new PlannerAmount(value);
    }

    public PlannerAmount add(PlannerAmount other) {
        Objects.requireNonNull(other, "other");
        if (wideValue == null && other.wideValue == null) {
            long a = fastValue;
            long b = other.fastValue;
            long sum = a + b;
            // Branch-only overflow test: exceptions would deoptimise the hot planner loops on every overflow.
            if (((a ^ sum) & (b ^ sum)) >= 0) return of(sum);
            return of(BigInteger.valueOf(a).add(BigInteger.valueOf(b)));
        }
        return of(toBigInteger().add(other.toBigInteger()));
    }

    public PlannerAmount add(long other) {
        return add(of(other));
    }

    public PlannerAmount subtract(PlannerAmount other) {
        Objects.requireNonNull(other, "other");
        if (wideValue == null && other.wideValue == null) {
            long a = fastValue;
            long b = other.fastValue;
            long difference = a - b;
            if (((a ^ b) & (a ^ difference)) >= 0) return of(difference);
            return of(BigInteger.valueOf(a).subtract(BigInteger.valueOf(b)));
        }
        return of(toBigInteger().subtract(other.toBigInteger()));
    }

    public PlannerAmount subtract(long other) {
        return subtract(of(other));
    }

    public PlannerAmount multiply(PlannerAmount other) {
        Objects.requireNonNull(other, "other");
        if (wideValue == null && other.wideValue == null) {
            long a = fastValue;
            long b = other.fastValue;
            long low = a * b;
            long high = Math.multiplyHigh(a, b);
            if ((high == 0L && low >= 0L) || (high == -1L && low < 0L)) return of(low);
            return of(BigInteger.valueOf(a).multiply(BigInteger.valueOf(b)));
        }
        return of(toBigInteger().multiply(other.toBigInteger()));
    }

    public PlannerAmount multiply(long other) {
        return multiply(of(other));
    }

    /** Exact ceiling division for non-negative values and a positive divisor. */
    public PlannerAmount ceilDiv(PlannerAmount divisor) {
        Objects.requireNonNull(divisor, "divisor");
        if (signum() < 0 || divisor.signum() <= 0) {
            throw new IllegalArgumentException("ceilDiv requires value >= 0 and divisor > 0");
        }
        if (wideValue == null && divisor.wideValue == null) {
            long quotient = fastValue / divisor.fastValue;
            return of(fastValue % divisor.fastValue == 0L ? quotient : quotient + 1L);
        }
        BigInteger[] result = toBigInteger().divideAndRemainder(divisor.toBigInteger());
        return of(result[1].signum() == 0 ? result[0] : result[0].add(BigInteger.ONE));
    }

    public PlannerAmount divide(PlannerAmount divisor) {
        Objects.requireNonNull(divisor, "divisor");
        if (divisor.isZero()) throw new ArithmeticException("division by zero");
        if (wideValue == null && divisor.wideValue == null) {
            if (fastValue == Long.MIN_VALUE && divisor.fastValue == -1L) {
                return of(BigInteger.valueOf(fastValue).divide(BigInteger.valueOf(divisor.fastValue)));
            }
            return of(fastValue / divisor.fastValue);
        }
        return of(toBigInteger().divide(divisor.toBigInteger()));
    }

    public PlannerAmount remainder(PlannerAmount divisor) {
        Objects.requireNonNull(divisor, "divisor");
        if (divisor.isZero()) throw new ArithmeticException("division by zero");
        if (wideValue == null && divisor.wideValue == null) {
            if (fastValue == Long.MIN_VALUE && divisor.fastValue == -1L) return ZERO;
            return of(fastValue % divisor.fastValue);
        }
        return of(toBigInteger().remainder(divisor.toBigInteger()));
    }

    /** Exact planner storage estimate for a stack amount. */
    public static PlannerAmount stackBytes(PlannerAmount amount, long amountPerByte) {
        Objects.requireNonNull(amount, "amount");
        if (amount.signum() < 0 || amountPerByte <= 0) throw new IllegalArgumentException("stack bytes");
        PlannerAmount divisor = of(amountPerByte);
        PlannerAmount whole = amount.divide(divisor).multiply(8L);
        PlannerAmount remainder = amount.remainder(divisor);
        PlannerAmount partial = remainder.multiply(8L).ceilDiv(divisor);
        return whole.add(partial);
    }

    public PlannerAmount min(PlannerAmount other) {
        return compareTo(other) <= 0 ? this : other;
    }

    public PlannerAmount max(PlannerAmount other) {
        return compareTo(other) >= 0 ? this : other;
    }

    public int signum() {
        return wideValue == null ? Long.compare(fastValue, 0L) : wideValue.signum();
    }

    public boolean isZero() {
        return signum() == 0;
    }

    public boolean fitsLong() {
        return wideValue == null;
    }

    public long longValueExact() {
        return wideValue == null ? fastValue : wideValue.longValueExact();
    }

    public BigInteger toBigInteger() {
        return wideValue == null ? BigInteger.valueOf(fastValue) : wideValue;
    }

    @Override
    public int compareTo(PlannerAmount other) {
        Objects.requireNonNull(other, "other");
        if (wideValue == null && other.wideValue == null) return Long.compare(fastValue, other.fastValue);
        // A wide value never fits in a long, so against a narrow value its sign alone decides the order.
        if (other.wideValue == null) return wideValue.signum();
        if (wideValue == null) return -other.wideValue.signum();
        return wideValue.compareTo(other.wideValue);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof PlannerAmount amount)) return false;
        if (wideValue == null) return amount.wideValue == null && fastValue == amount.fastValue;
        return wideValue.equals(amount.wideValue);
    }

    /** Identical to {@code toBigInteger().hashCode()}, computed without allocating for narrow values. */
    @Override
    public int hashCode() {
        if (wideValue != null) return wideValue.hashCode();
        if (fastValue == 0L) return 0;
        long magnitude = fastValue < 0 ? -fastValue : fastValue; // Long.MIN_VALUE stays 2^63 as unsigned bits
        int high = (int) (magnitude >>> 32);
        int low = (int) magnitude;
        int hash = 0;
        if (high != 0) hash = (int) (31 * hash + (high & 0xffffffffL));
        hash = (int) (31 * hash + (low & 0xffffffffL));
        return fastValue < 0 ? -hash : hash;
    }

    @Override
    public String toString() {
        return wideValue == null ? Long.toString(fastValue) : wideValue.toString();
    }

    private static boolean fitsLong(BigInteger value) {
        return value.compareTo(LONG_MIN) >= 0 && value.compareTo(LONG_MAX) <= 0;
    }
}
