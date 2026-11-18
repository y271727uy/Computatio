package com.y271727uy.computatio.api.multisetindex;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class MultisetSearchTest {
    @Test
    void findsValuesByAvailableFeatureCounts() {
        List<String> values = List.of("ab", "abc", "bc");
        FeatureMultiset available = features(1, 2);

        String any = MultisetSearch.findAny(values, MultisetSearchTest::featuresFor, available, value -> true);
        List<String> all = MultisetSearch.findAll(values, MultisetSearchTest::featuresFor, available, value -> true);
        MultisetIndex<String> index = MultisetIndex.build(
                values,
                MultisetSearchTest::featuresFor,
                MultisetIndex.Options.<String>defaults()
                        .onFeatureCountTable((value, counts) -> assertEquals(value.length() > 0, counts.matches(featuresFor(value)))));

        assertEquals("ab", any);
        assertEquals(List.of("ab"), all);
        assertEquals(3, index.size());
        assertEquals(3, index.indexedSize());
        assertEquals(0, index.fallbackSize());
        assertEquals("ab", index.findAny(available, value -> true));
        assertEquals(List.of("ab"), index.findAll(available, value -> true));
    }

    private static FeatureMultiset featuresFor(String value) {
        FeatureMultiset result = new FeatureMultiset();
        for (int i = 0; i < value.length(); i++) {
            result.add(value.charAt(i) - 'a' + 1, 1L);
        }
        return result;
    }

    private static FeatureMultiset features(int... ids) {
        FeatureMultiset result = new FeatureMultiset();
        for (int id : ids) {
            result.add(id, 1L);
        }
        return result;
    }
}
