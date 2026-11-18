package com.y271727uy.computatio.algorithm.multisetindex;

import java.util.List;
import java.util.function.Consumer;

public interface IndexNode<T> {
    static <T> IndexNode<T> createLeaf(List<Runnable> deferred, IndexNode<T> next, T value) {
        return new IndexLeaf<>(value);
    }
    static <T> IndexNode<T> createBranch(List<Runnable> deferred, IndexNode<T> next) {
        return new IndexBranchNodeImpl<>(IndexBranch.create(), next);
    }
    default T get(MultisetIndexSearcher<T> searcher, IndexSearchFrame<T> frame) { return null; }
    default boolean forEach(MultisetIndexSearcher<T> searcher, IndexSearchFrame<T> frame, Consumer<? super T> consumer) { return false; }

    interface IndexBranchNode<T> extends IndexNode<T> {
        IndexBranch<T> branch();
    }

    class IndexLeaf<T> implements IndexNode<T> {
        final T value;
        public IndexLeaf(T value) { this.value = value; }
        @Override public T get(MultisetIndexSearcher<T> searcher, IndexSearchFrame<T> frame) { return value; }
        @Override public boolean forEach(MultisetIndexSearcher<T> searcher, IndexSearchFrame<T> frame, Consumer<? super T> consumer) { consumer.accept(value); return true; }
    }

    final class IndexBranchLeaf<T> extends IndexLeaf<T> implements IndexBranchNode<T> {
        private final IndexBranch<T> indexBranch;
        private IndexBranchLeaf(IndexBranch<T> indexBranch, T value) { super(value); this.indexBranch = indexBranch; }
        public IndexBranch<T> branch() { return indexBranch; }
    }

    class IndexListLeaf<T> implements IndexNode<T> {
        final T[] values;
        public IndexListLeaf(T[] values) { this.values = values; }
        @Override public T get(MultisetIndexSearcher<T> searcher, IndexSearchFrame<T> frame) { return values.length == 0 ? null : values[0]; }
        @Override public boolean forEach(MultisetIndexSearcher<T> searcher, IndexSearchFrame<T> frame, Consumer<? super T> consumer) {
            for (T value : values) consumer.accept(value);
            return values.length != 0;
        }
    }

    final class IndexListBranchLeaf<T> extends IndexListLeaf<T> implements IndexBranchNode<T> {
        private final IndexBranch<T> indexBranch;
        private IndexListBranchLeaf(IndexBranch<T> indexBranch, T[] values) { super(values); this.indexBranch = indexBranch; }
        public IndexBranch<T> branch() { return indexBranch; }
    }

    final class TreeBranch<T> implements IndexBranchNode<T> {
        private IndexBranch<T> indexBranch = IndexBranch.create();
        private final java.util.List<T> values = new java.util.ArrayList<>();
        private final java.util.List<FeatureCountTable> tables = new java.util.ArrayList<>();
        public IndexBranch<T> branch() { return indexBranch; }
        public void add(T value, FeatureCountTable table) { values.add(value); tables.add(table); }
        public java.util.List<T> values() { return values; }
        public java.util.List<FeatureCountTable> tables() { return tables; }
        public void optimize() { indexBranch = indexBranch.optimize(); }
    }

    final class IndexBranchNodeImpl<T> implements IndexBranchNode<T> {
        private final IndexBranch<T> indexBranch;
        private final IndexNode<T> next;
        private IndexBranchNodeImpl(IndexBranch<T> indexBranch, IndexNode<T> next) { this.indexBranch = indexBranch; this.next = next; }
        public IndexBranch<T> branch() { return indexBranch; }
        public IndexNode<T> next() { return next; }
    }
}
