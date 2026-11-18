package com.y271727uy.computatio.algorithm.fastpath.cache;

import com.y271727uy.computatio.algorithm.fastpath.debug.*;

import com.y271727uy.computatio.algorithm.fastpath.execution.*;

import com.y271727uy.computatio.algorithm.fastpath.search.*;

import com.y271727uy.computatio.algorithm.fastpath.model.*;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongFunction;

/**
 * 以单调时钟为依据的有界 long 键缓存。
 * 缓存容器支持并发访问，但加载函数仍应由调用方在其资源所属线程执行，避免将平台世界对象暴露给异步线程。
 */
public final class ExpiringLongCache<T> {
    private final ConcurrentHashMap<Long, Entry<T>> entries = new ConcurrentHashMap<>();
    private final int maxEntries;
    private final long reuseTtl;

    public ExpiringLongCache(int maxEntries, long reuseTtl) {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        if (reuseTtl < 0L) {
            throw new IllegalArgumentException("reuseTtl must not be negative");
        }
        this.maxEntries = maxEntries;
        this.reuseTtl = reuseTtl;
    }

    public T getOrCompute(long key, long now, LongFunction<? extends T> loader) {
        Objects.requireNonNull(loader, "loader");
        Entry<T> entry = entries.compute(key, (ignored, existing) -> {
            if (existing != null && isReusable(existing, now)) {
                return existing;
            }
            T value = Objects.requireNonNull(loader.apply(key), "loader result");
            return new Entry<>(value, now);
        });
        return entry.value();
    }

    public void cleanup(long now) {
        entries.entrySet().removeIf(entry -> !isReusable(entry.getValue(), now));
        int removalAttempts = entries.size();
        while (entries.size() > maxEntries && removalAttempts-- > 0) {
            Map.Entry<Long, Entry<T>> oldest = oldestEntry();
            if (oldest == null || !entries.remove(oldest.getKey(), oldest.getValue())) {
                continue;
            }
        }
    }

    public void clear() {
        entries.clear();
    }

    public int size() {
        return entries.size();
    }

    private boolean isReusable(Entry<T> entry, long now) {
        return now - entry.createdAt() <= reuseTtl;
    }

    private Map.Entry<Long, Entry<T>> oldestEntry() {
        Map.Entry<Long, Entry<T>> oldest = null;
        for (Map.Entry<Long, Entry<T>> entry : entries.entrySet()) {
            if (oldest == null || entry.getValue().createdAt() < oldest.getValue().createdAt()) {
                oldest = entry;
            }
        }
        return oldest;
    }

    private record Entry<T>(T value, long createdAt) {
    }
}
