package com.y271727uy.computatio.algorithm.fastpath.cache;

import com.y271727uy.computatio.algorithm.fastpath.debug.*;

import com.y271727uy.computatio.algorithm.fastpath.execution.*;

import com.y271727uy.computatio.algorithm.fastpath.search.*;

import com.y271727uy.computatio.algorithm.fastpath.model.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ExpiringLongCacheTest {
    @Test
    void reusesValuesOnlyInsideTheConfiguredWindow() {
        ExpiringLongCache<String> cache = new ExpiringLongCache<>(2, 4L);
        AtomicInteger loads = new AtomicInteger();

        assertEquals("value-1", cache.getOrCompute(1L, 10L, ignored -> "value-" + loads.incrementAndGet()));
        assertEquals("value-1", cache.getOrCompute(1L, 14L, ignored -> "value-" + loads.incrementAndGet()));
        assertEquals("value-2", cache.getOrCompute(1L, 15L, ignored -> "value-" + loads.incrementAndGet()));
    }

    @Test
    void removesOldestValuesWhenCapacityIsExceeded() {
        ExpiringLongCache<String> cache = new ExpiringLongCache<>(2, 20L);
        cache.getOrCompute(1L, 1L, ignored -> "first");
        cache.getOrCompute(2L, 2L, ignored -> "second");
        cache.getOrCompute(3L, 3L, ignored -> "third");

        cache.cleanup(3L);

        assertEquals(2, cache.size());
        assertEquals("replacement", cache.getOrCompute(1L, 3L, ignored -> "replacement"));
    }
}
