package com.y271727uy.computatio.api.multisetindex;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

public final class MultisetSearch {
    private MultisetSearch() {
    }

    public static <R> R findAny(
            Collection<R> values,
            Function<R, FeatureMultiset> featureExtractor,
            FeatureMultiset available,
            Predicate<R> predicate) {
        return build(values, featureExtractor).findAny(available, predicate);
    }

    public static <R> List<R> findAll(
            Collection<R> values,
            Function<R, FeatureMultiset> featureExtractor,
            FeatureMultiset available,
            Predicate<R> predicate) {
        return build(values, featureExtractor).findAll(available, predicate);
    }

    private static <R> MultisetIndex<R> build(
            Collection<R> values, Function<R, FeatureMultiset> featureExtractor) {
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(featureExtractor, "featureExtractor");
        return MultisetIndex.build(values, featureExtractor);
    }
}
