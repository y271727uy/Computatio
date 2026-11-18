package com.y271727uy.computatio.api.multisetindex;

import com.y271727uy.computatio.algorithm.multisetindex.AbstractMultisetIndex;
import com.y271727uy.computatio.algorithm.multisetindex.FeatureCountTable;
import com.y271727uy.computatio.algorithm.multisetindex.IntLongMultiset;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;

public final class MultisetIndex<R> {
    private final IndexedDatabase<R> database;

    private MultisetIndex(IndexedDatabase<R> database) {
        this.database = database;
    }

    public static <R> MultisetIndex<R> build(
            Collection<R> values, Function<R, FeatureMultiset> featureExtractor) {
        return build(values, featureExtractor, Options.defaults());
    }

    public static <R> MultisetIndex<R> build(
            Collection<R> values,
            Function<R, FeatureMultiset> featureExtractor,
            Options<R> options) {
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(featureExtractor, "featureExtractor");
        Objects.requireNonNull(options, "options");
        IndexedDatabase<R> database = new IndexedDatabase<>(featureExtractor, options);
        AbstractMultisetIndex.build(database, values);
        return new MultisetIndex<>(database);
    }

    public R findAny(FeatureMultiset available, Predicate<R> predicate) {
        Objects.requireNonNull(available, "available");
        Objects.requireNonNull(predicate, "predicate");
        return database.findAnyMatch(available.internal(), available.keys(), predicate);
    }

    public List<R> findAll(FeatureMultiset available, Predicate<R> predicate) {
        Objects.requireNonNull(available, "available");
        Objects.requireNonNull(predicate, "predicate");
        return database.search(available.internal(), available.keys(), predicate).stream().toList();
    }

    public int size() {
        return database.valueCount;
    }
    public int indexedSize() {
        return database.indexedValueCount;
    }

    public int fallbackSize() {
        return database.fallbackValueCount;
    }

    public static final class Options<R> {
        private final Predicate<R> supportsParallel;
        private final BiConsumer<R, FeatureCounts> tableConsumer;
        private final Comparator<? super R> fallbackComparator;

        private Options(Predicate<R> supportsParallel, BiConsumer<R, FeatureCounts> tableConsumer,
                        Comparator<? super R> fallbackComparator) {
            this.supportsParallel = Objects.requireNonNull(supportsParallel, "supportsParallel");
            this.tableConsumer = Objects.requireNonNull(tableConsumer, "tableConsumer");
            this.fallbackComparator = fallbackComparator;
        }

        public static <R> Options<R> defaults() {
            return new Options<>(value -> true, (value, table) -> { }, null);
        }

        public Options<R> supportsParallel(Predicate<R> policy) {
            return new Options<>(policy, tableConsumer, fallbackComparator);
        }

        public Options<R> onFeatureCountTable(BiConsumer<R, FeatureCounts> consumer) {
            return new Options<>(supportsParallel, consumer, fallbackComparator);
        }

        public Options<R> fallbackComparator(Comparator<? super R> comparator) {
            return new Options<>(supportsParallel, tableConsumer,
                    Objects.requireNonNull(comparator, "comparator"));
        }
    }

    private static final class IndexedDatabase<R> extends AbstractMultisetIndex<R> {
        private final Function<R, FeatureMultiset> featureExtractor;
        private final Options<R> options;
        private int valueCount;
        private int indexedValueCount;
        private int fallbackValueCount;

        private IndexedDatabase(Function<R, FeatureMultiset> featureExtractor, Options<R> options) {
            this.featureExtractor = featureExtractor;
            this.options = options;
        }

        @Override
        protected IntLongMultiset extractFeatureMultiset(R value) {
            valueCount++;
            FeatureMultiset features = FeatureMultiset.require(
                    featureExtractor.apply(value), "feature extractor result");
            if (features.size() == 0) {
                fallbackValueCount++;
            } else {
                indexedValueCount++;
            }
            return features.internal();
        }

        @Override
        protected void setFeatureCountTable(R value, FeatureCountTable table) {
            options.tableConsumer.accept(value, available -> table.match(available.internal()));
        }

        @Override
        protected boolean supportsParallel(R value) {
            return options.supportsParallel.test(value);
        }

        @Override
        protected void finishBuild() {
            super.finishBuild();
            if (options.fallbackComparator != null) {
                unindexedSerial.sort(options.fallbackComparator);
            }
        }
    }
}
