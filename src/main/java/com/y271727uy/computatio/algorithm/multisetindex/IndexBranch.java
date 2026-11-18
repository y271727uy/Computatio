package com.y271727uy.computatio.algorithm.multisetindex;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import java.util.Arrays;

public interface IndexBranch<R> {
    IndexNode<R> get(int key);
    int size();
    int[] key();
    IndexNode<R>[] value();

    default IndexBranch<R> optimize() { return this; }

    static <R> IndexBranch<R> create() { return new HashIndexBranch<>(); }

    final class HashIndexBranch<R> extends Int2ObjectOpenHashMap<IndexNode<R>> implements IndexBranch<R> {
        private int[] keys = new int[0];
        private IndexNode<R>[] values = emptyNodes();
        private boolean dirty = true;

        @SuppressWarnings("unchecked")
        private static <R> IndexNode<R>[] emptyNodes() { return (IndexNode<R>[]) new IndexNode<?>[0]; }

        @Override public IndexNode<R> put(int key, IndexNode<R> value) {
            dirty = true;
            return super.put(key, value);
        }
        @Override public IndexNode<R> get(int key) { return super.get(key); }
        @Override public int size() { return super.size(); }
        @Override public int[] key() { refresh(); return keys; }
        @Override public IndexNode<R>[] value() { refresh(); return values; }
        @Override public IndexBranch<R> optimize() {
            refresh();
            for (IndexNode<R> node : values) {
                if (node instanceof IndexNode.TreeBranch<R> tree) tree.optimize();
            }
            return keys.length < 5 ? new ArrayIndexBranch<>(keys, values) : this;
        }

        @Override public String toString() { return super.toString(); }

        @SuppressWarnings("unchecked")
        private void refresh() {
            if (!dirty) return;
            keys = keySet().toIntArray();
            Arrays.sort(keys);
            values = (IndexNode<R>[]) new IndexNode<?>[keys.length];
            for (int i = 0; i < keys.length; i++) values[i] = super.get(keys[i]);
            dirty = false;
        }
    }

    final class ArrayIndexBranch<R> implements IndexBranch<R> {
        private final int[] keys;
        private final IndexNode<R>[] values;

        private ArrayIndexBranch(int[] keys, IndexNode<R>[] values) {
            this.keys = keys;
            this.values = values;
        }

        @Override public IndexNode<R> get(int key) {
            for (int i = 0; i < keys.length; i++) {
                if (keys[i] == key) return values[i];
            }
            return null;
        }

        @Override public int size() { return keys.length; }
        @Override public int[] key() { return keys; }
        @Override public IndexNode<R>[] value() { return values; }
    }
}
