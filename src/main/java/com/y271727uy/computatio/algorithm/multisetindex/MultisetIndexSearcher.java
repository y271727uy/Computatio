package com.y271727uy.computatio.algorithm.multisetindex;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/** Iterator over the candidates returned by a multiset-index search. */
public final class MultisetIndexSearcher<R> implements Iterator<R>, Iterable<R> {
    private List<R> results = List.of();
    private int cursor;

    public MultisetIndexSearcher() {}
    public MultisetIndexSearcher(int maxDepth) {}
    public MultisetIndexSearcher(List<R> results) { this.results = List.copyOf(results); }
    public MultisetIndexSearcher(int maxDepth, IndexBranch<R> root, IntLongMultiset available, int[] inputKeys,
                                 Predicate<R> predicate, Iterator<R> fallback) {
        reset(maxDepth, root, available, inputKeys, predicate, fallback);
    }

    public void reset(int maxDepth, IndexBranch<R> root, IntLongMultiset available, int[] inputKeys,
                      Predicate<R> predicate, Iterator<R> fallback) {
        List<R> found = new ArrayList<>();
        if (root != null && available != null) collect(root, available, predicate, found);
        if (fallback != null) fallback.forEachRemaining(found::add);
        results = found;
        cursor = 0;
    }

    private void collect(IndexBranch<R> indexBranch, IntLongMultiset available, Predicate<R> predicate, List<R> found) {
        for (int key : indexBranch.key()) {
            if (available.get(key) <= 0) continue;
            IndexNode<R> indexNode = indexBranch.get(key);
            if (indexNode instanceof IndexNode.TreeBranch<R> tree) {
                for (int i = 0; i < tree.values().size(); i++) {
                    R value = tree.values().get(i);
                    if (tree.tables().get(i).match(available) && predicate.test(value)) found.add(value);
                }
                collect(tree.branch(), available, predicate, found);
            }
        }
    }

    public R findAny() { return hasNext() ? next() : null; }
    @Override public boolean hasNext() { return cursor < results.size(); }
    @Override public R next() { if (!hasNext()) throw new NoSuchElementException(); return results.get(cursor++); }
    @Override public Iterator<R> iterator() { return this; }
    @Override public void forEach(Consumer<? super R> action) { while (hasNext()) action.accept(next()); }
    @Override public Spliterator<R> spliterator() { return results.subList(cursor, results.size()).spliterator(); }
    @Override public void forEachRemaining(Consumer<? super R> action) { while (hasNext()) action.accept(next()); }
    public Stream<R> stream() { return StreamSupport.stream(spliterator(), false); }
}
