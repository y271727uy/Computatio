package com.y271727uy.computatio.api.multisetindex;

import com.y271727uy.computatio.algorithm.multisetindex.IntLongMultiset;

import java.util.Arrays;
import java.util.Objects;

public final class FeatureMultiset {
    private final IntLongMultiset delegate;

    public FeatureMultiset() {
        this.delegate = new IntLongMultiset();
    }

    public FeatureMultiset(int expectedSize) {
        this.delegate = new IntLongMultiset(expectedSize);
    }
    public FeatureMultiset add(int key, long amount) {
        delegate.add(key, amount);
        return this;
    }

    public FeatureMultiset set(int key, long amount) {
        delegate.set(key, amount);
        return this;
    }

    public long get(int key) {
        return delegate.get(key);
    }

    public int[] keys() {
        int[] keys = delegate.toIntArray();
        return Arrays.copyOf(keys, keys.length);
    }

    public int size() {
        return delegate.size();
    }

    IntLongMultiset internal() {
        return delegate;
    }

    static FeatureMultiset require(FeatureMultiset value, String name) {
        return Objects.requireNonNull(value, name);
    }
}
