package com.example.mysqlbot.service;

import com.example.mysqlbot.model.DataSource;
import com.example.mysqlbot.repository.DataSourceRepository;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared HikariCP connection-pool service.
 *
 * <p>Extracted from SqlExecuteService so that ToolService, SchemaService,
 * and SQL execution all reuse the same pool per data source, eliminating the
 * per-call DriverManager.getConnection overhead in tool-driven schema exploration.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectionPoolService {

    private final DataSourceRepository dataSourceRepository;

    private final ConcurrentHashMap<Long, HikariDataSource> pools = new ConcurrentHashMap<>();

    private static final int MAX_POOL_SIZE = 10;

    /**
     * Returns a pooled connection for the given data-source ID.
     * Caller must close the connection (returns it to the pool).
     */
    public Connection getConnection(Long dataSourceId) throws SQLException {
        DataSource ds = dataSourceRepository.findById(dataSourceId)
                .orElseThrow(() -> new RuntimeException("Data source not found: " + dataSourceId));
        return getConnection(ds);
    }

    /**
     * Returns a pooled connection for the given DataSource entity.
     * Used by services that already hold the entity (e.g. SchemaService).
     */
    public Connection getConnection(DataSource ds) throws SQLException {
        return getPool(ds).getConnection();
    }

    /**
     * Closes and removes the pool for the given data source.
     * Call after config changes or deletion so stale connections are dropped.
     */
    public void evictPool(Long dataSourceId) {
        HikariDataSource pool = pools.remove(dataSourceId);
        if (pool != null && !pool.isClosed()) {
            pool.close();
            log.info("Evicted connection pool for dataSourceId={}", dataSourceId);
        }
    }

    @PreDestroy
    public void cleanup() {
        pools.forEach((id, pool) -> {
            if (!pool.isClosed()) pool.close();
            log.info("Closed connection pool for dataSourceId={}", id);
        });
        pools.clear();
    }

    private HikariDataSource getPool(DataSource ds) {
        return pools.computeIfAbsent(ds.getId(), id -> createPool(ds));
    }

    private HikariDataSource createPool(DataSource ds) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(ds.buildJdbcUrl());
        config.setUsername(ds.getUsername());
        config.setPassword(ds.getPassword());
        config.setMaximumPoolSize(MAX_POOL_SIZE);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(10000);
        config.setIdleTimeout(300000);
        config.setMaxLifetime(600000);
        config.setPoolName("ds-" + ds.getId());
        log.info("Created connection pool for dataSourceId={}, name={}", ds.getId(), ds.getName());
        return new HikariDataSource(config);
    }
}
