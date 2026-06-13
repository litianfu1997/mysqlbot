package com.example.mysqlbot.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SchemaCacheService}.
 * Covers TTL expiry, cache hit/miss, and per-dataSource eviction.
 */
class SchemaCacheServiceTest {

    private SchemaCacheService service;

    @BeforeEach
    void setUp() {
        service = new SchemaCacheService();
        // @Value won't be processed in plain unit test; set manually
        ReflectionTestUtils.setField(service, "ttlSeconds", 1L); // 1-second TTL for fast tests
    }

    // ---- Table names ----

    @Test
    void tableNames_cacheMiss_returnsNull() {
        assertNull(service.getTableNames(1L));
    }

    @Test
    void tableNames_cacheHit_returnsStoredValue() {
        List<String> tables = List.of("orders", "users", "products");
        service.putTableNames(1L, tables);
        assertEquals(tables, service.getTableNames(1L));
    }

    @Test
    void tableNames_independentPerDataSource() {
        service.putTableNames(1L, List.of("t1"));
        service.putTableNames(2L, List.of("t2"));
        assertEquals(List.of("t1"), service.getTableNames(1L));
        assertEquals(List.of("t2"), service.getTableNames(2L));
    }

    // ---- Table schema ----

    @Test
    void tableSchema_cacheMiss_returnsNull() {
        assertNull(service.getTableSchema(1L, "orders"));
    }

    @Test
    void tableSchema_cacheHit_returnsStoredValue() {
        String schema = "table orders: id int, name varchar";
        service.putTableSchema(1L, "orders", schema);
        assertEquals(schema, service.getTableSchema(1L, "orders"));
    }

    @Test
    void tableSchema_independentPerTable() {
        service.putTableSchema(1L, "orders", "schema-a");
        service.putTableSchema(1L, "users", "schema-b");
        assertEquals("schema-a", service.getTableSchema(1L, "orders"));
        assertEquals("schema-b", service.getTableSchema(1L, "users"));
    }

    // ---- Relations ----

    @Test
    void relations_cacheHitAndMiss() {
        assertNull(service.getRelations(1L, "all"));
        String rels = "orders.user_id -> users.id";
        service.putRelations(1L, "all", rels);
        assertEquals(rels, service.getRelations(1L, "all"));
    }

    // ---- TTL expiry ----

    @Test
    void entryExpiresAfterTtl() throws InterruptedException {
        service.putTableNames(1L, List.of("orders"));
        assertNotNull(service.getTableNames(1L)); // within TTL

        Thread.sleep(1100); // wait past TTL

        assertNull(service.getTableNames(1L)); // expired
    }

    @Test
    void entryWithinTtl_isStillValid() throws InterruptedException {
        service.putTableSchema(1L, "orders", "schema");
        Thread.sleep(300); // well within 1s TTL
        assertEquals("schema", service.getTableSchema(1L, "orders"));
    }

    // ---- Eviction ----

    @Test
    void evictDataSource_removesAllEntriesForThatSource() {
        service.putTableNames(1L, List.of("t"));
        service.putTableSchema(1L, "orders", "schema");
        service.putRelations(1L, "all", "rels");

        service.evictDataSource(1L);

        assertNull(service.getTableNames(1L));
        assertNull(service.getTableSchema(1L, "orders"));
        assertNull(service.getRelations(1L, "all"));
    }

    @Test
    void evictDataSource_doesNotAffectOtherDataSources() {
        service.putTableNames(1L, List.of("t1"));
        service.putTableSchema(1L, "orders", "schema1");
        service.putTableNames(2L, List.of("t2"));
        service.putTableSchema(2L, "orders", "schema2");

        service.evictDataSource(1L);

        // DS 1 entries gone
        assertNull(service.getTableNames(1L));
        assertNull(service.getTableSchema(1L, "orders"));
        // DS 2 entries preserved
        assertEquals(List.of("t2"), service.getTableNames(2L));
        assertEquals("schema2", service.getTableSchema(2L, "orders"));
    }

    @Test
    void evictDataSource_safeWhenEmpty() {
        assertDoesNotThrow(() -> service.evictDataSource(999L));
    }

    // ---- After eviction, fresh entries can be stored ----

    @Test
    void canRepopulateAfterEviction() {
        service.putTableNames(1L, List.of("old"));
        service.evictDataSource(1L);
        assertNull(service.getTableNames(1L));

        service.putTableNames(1L, List.of("new"));
        assertEquals(List.of("new"), service.getTableNames(1L));
    }
}