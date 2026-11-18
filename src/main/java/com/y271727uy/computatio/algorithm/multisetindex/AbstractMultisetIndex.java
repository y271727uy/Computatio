package com.y271727uy.computatio.algorithm.multisetindex;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrays;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/** Builds and queries a feature-count tree for arbitrary indexed values. */
public abstract class AbstractMultisetIndex<R> {
    protected IndexBranch<R> rootIndexBranch;
    protected List<R> unindexedSerial;
    protected List<R> unindexedParallel;
    protected int maxSearchDepth;
    protected int minParallelThreshold = 1000;
    protected final Map<R, FeatureCountTable> tables = new IdentityHashMap<>();

    public AbstractMultisetIndex() { clear(); }
    protected abstract IntLongMultiset extractFeatureMultiset(R value);
    protected abstract void setFeatureCountTable(R value, FeatureCountTable table);
    protected boolean supportsParallel(R value) { return true; }

    public static <R, DB extends AbstractMultisetIndex<R>> DB build(DB db, Collection<R> values) {
        db.clear();
        Int2IntOpenHashMap frequency = new Int2IntOpenHashMap();
        List<FeatureData<R>> indexed = new ArrayList<>();
        for (R value : values) {
            if (value == null) continue;
            IntLongMultiset map = db.extractFeatureMultiset(value);
            int[] ids = map.toIntArray();
            if (ids.length == 0) {
                (db.supportsParallel(value) ? db.unindexedParallel : db.unindexedSerial).add(value);
                continue;
            }
            FeatureCountTable table = new FeatureCountTable(ids);
            table.amounts = new long[ids.length];
            for (int i = 0; i < ids.length; i++) {
                table.amounts[i] = map.get(ids[i]);
                frequency.addTo(ids[i], 1);
            }
            db.setFeatureCountTable(value, table);
            db.tables.put(value, table);
            indexed.add(new FeatureData<>(value, ids, table));
        }
        for (FeatureData<R> data : indexed) {
            IntArrays.stableSort(data.ids, (a, b) -> Integer.compare(frequency.get(a), frequency.get(b)));
            db.maxSearchDepth = Math.max(db.maxSearchDepth, data.ids.length);
            insert(db.rootIndexBranch, data.ids, 0, data.value, data.table);
        }
        db.rootIndexBranch = db.rootIndexBranch.optimize();
        db.finishBuild();
        return db;
    }

    private static <R> void insert(IndexBranch<R> indexBranch, int[] path, int index, R value, FeatureCountTable table) {
        IndexBranch.HashIndexBranch<R> hashBranch = (IndexBranch.HashIndexBranch<R>) indexBranch;
        IndexNode.TreeBranch<R> node = (IndexNode.TreeBranch<R>) hashBranch.get(path[index]);
        if (node == null) {
            node = new IndexNode.TreeBranch<>();
            hashBranch.put(path[index], node);
        }
        if (index == path.length - 1) node.add(value, table);
        else insert(node.branch(), path, index + 1, value, table);
    }

    public R findAnyMatch(IntLongMultiset available, int[] inputKeys, Predicate<R> predicate) {
        if (available != null && rootIndexBranch != null) {
            R found = findAnyInIndex(rootIndexBranch, available, inputKeys, predicate);
            if (found != null) return found;
        }
        if (!unindexedParallel.isEmpty()) {
            R found = findInParallel(unindexedParallel, predicate);
            if (found != null) return found;
        }
        return unindexedSerial.isEmpty() ? null : findInSerial(unindexedSerial, predicate);
    }

    private R findAnyInIndex(IndexBranch<R> indexBranch, IntLongMultiset available,
                              int[] inputKeys, Predicate<R> predicate) {
        if (inputKeys.length <= indexBranch.size()) {
            for (int key : inputKeys) {
                IndexNode<R> indexNode = indexBranch.get(key);
                if (indexNode instanceof IndexNode.TreeBranch<R> tree) {
                    R found = findAnyInTree(tree, available, inputKeys, predicate);
                    if (found != null) return found;
                }
            }
            return null;
        }
        int[] keys = indexBranch.key();
        for (int key : keys) {
            if (available.get(key) <= 0) continue;
            IndexNode<R> indexNode = indexBranch.get(key);
            if (indexNode instanceof IndexNode.TreeBranch<R> tree) {
                R found = findAnyInTree(tree, available, inputKeys, predicate);
                if (found != null) return found;
            }
        }
        return null;
    }

    private R findAnyInTree(IndexNode.TreeBranch<R> tree, IntLongMultiset available,
                            int[] inputKeys, Predicate<R> predicate) {
        List<R> values = tree.values();
        List<FeatureCountTable> tables = tree.tables();
        for (int i = 0; i < values.size(); i++) {
            if (tables.get(i).match(available) && predicate.test(values.get(i))) {
                return values.get(i);
            }
        }
        return findAnyInIndex(tree.branch(), available, inputKeys, predicate);
    }

    protected static <R> R findInParallel(List<R> values, Predicate<R> predicate) {
        return values.parallelStream().filter(predicate).findAny().orElse(null);
    }
    protected static <R> R findInSerial(List<R> values, Predicate<R> predicate) {
        for (R value : values) if (predicate.test(value)) return value;
        return null;
    }

    public Iterator<R> createFallbackIterator(Predicate<R> predicate) {
        List<R> matches = unindexedParallel.parallelStream()
                .filter(predicate)
                .collect(Collectors.toList());
        unindexedSerial.stream().filter(predicate).forEach(matches::add);
        return matches.iterator();
    }
    public Iterable<R> searchFallback(Predicate<R> predicate) {
        List<R> matches = new ArrayList<>();
        createFallbackIterator(predicate).forEachRemaining(matches::add);
        return matches;
    }
    public MultisetIndexSearcher<R> search(IntLongMultiset available, int[] inputKeys, Predicate<R> predicate) {
        List<R> matches = new ArrayList<>();
        if (rootIndexBranch != null && available != null) collect(rootIndexBranch, available, inputKeys, predicate, matches);
        createFallbackIterator(predicate).forEachRemaining(matches::add);
        return new MultisetIndexSearcher<>(matches);
    }

    private void collect(IndexBranch<R> indexBranch, IntLongMultiset available, int[] inputKeys,
                         Predicate<R> predicate, List<R> results) {
        if (inputKeys.length <= indexBranch.size()) {
            for (int key : inputKeys) {
                IndexNode<R> indexNode = indexBranch.get(key);
                if (indexNode instanceof IndexNode.TreeBranch<R> tree) {
                    collectTree(tree, available, inputKeys, predicate, results);
                }
            }
            return;
        }
        for (int key : indexBranch.key()) {
            if (available.get(key) <= 0) continue;
            IndexNode<R> indexNode = indexBranch.get(key);
            if (indexNode instanceof IndexNode.TreeBranch<R> tree) {
                collectTree(tree, available, inputKeys, predicate, results);
            }
        }
    }

    private void collectTree(IndexNode.TreeBranch<R> tree, IntLongMultiset available, int[] inputKeys,
                             Predicate<R> predicate, List<R> results) {
        List<R> values = tree.values();
        List<FeatureCountTable> tables = tree.tables();
        for (int i = 0; i < values.size(); i++) {
            if (tables.get(i).match(available) && predicate.test(values.get(i))) {
                results.add(values.get(i));
            }
        }
        collect(tree.branch(), available, inputKeys, predicate, results);
    }

    protected void finishBuild() {
        if (unindexedParallel.size() < minParallelThreshold) {
            unindexedSerial.addAll(unindexedParallel);
            unindexedParallel = Collections.emptyList();
        }
        if (unindexedSerial.isEmpty()) unindexedSerial = Collections.emptyList();
    }
    protected void addToBranch(List<Runnable> deferred, R value, FeatureCountTable table) {}
    protected void addToBranch(List<Runnable> deferred, R value, int length, int[] ids, IndexBranch<R> indexBranch) {}
    protected void collectFeatureData(Int2IntOpenHashMap frequency, it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap<R, it.unimi.dsi.fastutil.Pair<IntLongMultiset, FeatureCountTable>> data, R value) {}
    protected void reorderByFrequency(Int2IntOpenHashMap frequency, it.unimi.dsi.fastutil.Pair<IntLongMultiset, FeatureCountTable> data) {}
    public void clear() {
        rootIndexBranch = IndexBranch.create();
        unindexedSerial = new ArrayList<>();
        unindexedParallel = new ArrayList<>();
        maxSearchDepth = 0;
        tables.clear();
    }

    private record FeatureData<R>(R value, int[] ids, FeatureCountTable table) {}
}
