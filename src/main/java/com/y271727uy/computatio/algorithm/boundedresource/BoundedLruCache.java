package com.y271727uy.computatio.algorithm.boundedresource;

import java.util.LinkedHashMap;
import java.util.Map;

/** Access-ordered, bounded LRU cache. */
public final class BoundedLruCache<K, V> {
    private final int capacity;
    private final Map<K, V> entries;

    public BoundedLruCache(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
        this.entries = new LinkedHashMap<>(capacity, 0.75F, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > BoundedLruCache.this.capacity;
            }
        };
    }

    public V get(K key) { return entries.get(key); }
    public void put(K key, V value) { entries.put(key, value); }
    public V putIfAbsent(K key, V value) { return entries.putIfAbsent(key, value); }
    public boolean containsKey(K key) { return entries.containsKey(key); }
    public int size() { return entries.size(); }
    public void clear() { entries.clear(); }
}
