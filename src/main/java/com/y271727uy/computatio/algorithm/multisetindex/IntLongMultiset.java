package com.y271727uy.computatio.algorithm.multisetindex;

import it.unimi.dsi.fastutil.ints.Int2LongMap;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;

import java.util.Arrays;

/** Primitive feature-count multiset used by the multiset index. */
public class IntLongMultiset extends Int2LongOpenHashMap implements Iterable<Int2LongMap.Entry> {
    public static final IntLongMultiset EMPTY = new Empty();

    public IntLongMultiset() { super(); }
    public IntLongMultiset(int expected) { super(expected); }
    public IntLongMultiset(IntLongMultiset other) { super(other); }

    public final long addTo(int key, long amount) { return super.addTo(key, amount); }
    public final long put(int key, long amount) { return super.put(key, amount); }
    public final long get(int key) { return super.get(key); }
    public void add(int key, long amount) { addTo(key, amount); }
    public void set(int key, long amount) { put(key, amount); }
    public void addAll(IntLongMultiset other) { other.forEach(e -> addTo(e.getIntKey(), e.getLongValue())); }
    public void setAll(IntLongMultiset other) { other.forEach(e -> put(e.getIntKey(), e.getLongValue())); }
    public final void addTo(IntLongMultiset other) { addAll(other); }
    public final void setTo(IntLongMultiset other) { setAll(other); }

    public final int copyTo(int[] keys, long[] amounts) {
        int i = 0;
        for (Int2LongMap.Entry entry : this) {
            keys[i] = entry.getIntKey();
            amounts[i++] = entry.getLongValue();
        }
        return i;
    }

    public int[] toIntArray() {
        int[] keys = keySet().toIntArray();
        Arrays.sort(keys);
        return keys;
    }

    @Override
    public java.util.Iterator<Int2LongMap.Entry> iterator() {
        return int2LongEntrySet().iterator();
    }

    public static final class Empty extends IntLongMultiset {
        private Empty() { super(); }
    }
}
