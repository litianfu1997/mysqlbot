package com.example.mysqlbot.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple TTL-based in-memory cache for database schema metadata.
 *
 * <p>Caches table-name lists, per-table column schemas, and table relations
 * so that repeated tool calls within an agent loop (or across nearby requests)
 * avoid redundant JDBC metadata round-trips. No external cache dependency.
 *
 * <p>Sample data ({@code get_sample_data}) is intentionally NOT cached because it
 * returns live row content rather than structural metadata.
 */
@Slf4j
@Service
public class SchemaCacheService {

    private record CacheEntry<T>(T value, long expiresAt) {
        boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }

    private final ConcurrentHashMap<String, CacheEntry<?>> cache = new ConcurrentHashMap<>();

    @Value("${mysqlbot.cache.schema-ttl-seconds:300}")
    private long ttlSeconds;

    // ---- Table name list ----

    @SuppressWarnings("unchecked")
    public List<String> getTableNames(Long dataSourceId) {
        return get(keyTables(dataSourceId), List.class);
    }

    public void putTableNames(Long dataSourceId, List<String> tables) {
        put(keyTables(dataSourceId), tables);
    }

    // ---- Per-table schema string ----

    public String getTableSchema(Long dataSourceId, String tableName) {
        return get(keySchema(dataSourceId, tableName), String.class);
    }

    public void putTableSchema(Long dataSourceId, String tableName, String schema) {
        put(keySchema(dataSourceId, tableName), schema);
    }

    // ---- Relations string ----

    public String getRelations(Long dataSourceId, String scope) {
        return get(keyRelations(dataSourceId, scope), String.class);
    }

    public void putRelations(Long dataSourceId, String scope, String relations) {
        put(keyRelations(dataSourceId, scope), relations);
    }

    // ---- Eviction ----

    /**
     * Removes all cached entries for the given data source.
     */
    public void evictDataSource(Long dataSourceId) {
        String prefix = "ds:" + dataSourceId + ":";
        int removed = 0;
        var it = cache.keySet().iterator();
        while (it.hasNext()) {
            if (it.next().startsWith(prefix)) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            log.info("Schema cache: evicted {} entries for dataSourceId={}", removed, dataSourceId);
        }
    }

    // ---- Internal helpers ----

    @SuppressWarnings("unchecked")
    private <T> T get(String key, Class<T> type) {
        CacheEntry<?> entry = cache.get(key);
        if (entry == null) return null;
        if (entry.isExpired()) {
            cache.remove(key);
            return null;
        }
        log.debug("Schema cache HIT: {}", key);
        return (T) entry.value();
    }

    private <T> void put(String key, T value) {
        long expiresAt = System.currentTimeMillis() + ttlSeconds * 1000;
        cache.put(key, new CacheEntry<>(value, expiresAt));
    }

    private static String keyTables(Long dsId) {
        return "ds:" + dsId + ":tables";
    }

    private static String keySchema(Long dsId, String table) {
        return "ds:" + dsId + ":schema:" + (table != null ? table : "all");
    }

    private static String keyRelations(Long dsId, String scope) {
        return "ds:" + dsId + ":relations:" + (scope != null ? scope : "all");
    }
}
