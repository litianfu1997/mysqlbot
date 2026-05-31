package com.example.mysqlbot.service;

import com.example.mysqlbot.model.DataSource;
import com.example.mysqlbot.repository.DataSourceRepository;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Select;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SQL execution service with connection pooling per DataSource.
 * Uses HikariCP to maintain a pool for each target database.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SqlExecuteService {

    private final DataSourceRepository dataSourceRepository;

    // Connection pools per data source
    private final ConcurrentHashMap<Long, HikariDataSource> connectionPools = new ConcurrentHashMap<>();

    @Value("${mysqlbot.sql.allow-only-select:true}")
    private boolean allowOnlySelect;

    @Value("${mysqlbot.sql.max-rows:1000}")
    private int maxRows;

    @Value("${mysqlbot.sql.timeout-seconds:30}")
    private int timeoutSeconds;

    @PreDestroy
    public void cleanup() {
        connectionPools.forEach((id, pool) -> {
            if (!pool.isClosed()) pool.close();
            log.info("Closed connection pool for dataSourceId={}", id);
        });
        connectionPools.clear();
    }

    /**
     * Get or create a connection pool for the given data source.
     */
    private HikariDataSource getPool(DataSource ds) {
        return connectionPools.computeIfAbsent(ds.getId(), id -> {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(ds.buildJdbcUrl());
            config.setUsername(ds.getUsername());
            config.setPassword(ds.getPassword());
            config.setMaximumPoolSize(5);
            config.setMinimumIdle(1);
            config.setConnectionTimeout(10000); // 10s
            config.setIdleTimeout(300000); // 5 min
            config.setMaxLifetime(600000); // 10 min
            config.setPoolName("ds-" + id);
            log.info("Created connection pool for dataSourceId={}, name={}", id, ds.getName());
            return new HikariDataSource(config);
        });
    }

    /**
     * Evict connection pool when data source is updated or deleted.
     */
    public void evictPool(Long dataSourceId) {
        HikariDataSource pool = connectionPools.remove(dataSourceId);
        if (pool != null && !pool.isClosed()) {
            pool.close();
            log.info("Evicted connection pool for dataSourceId={}", dataSourceId);
        }
    }

    public SqlExecuteResult execute(String sql, Long dataSourceId) {
        validateSql(sql);

        DataSource ds = dataSourceRepository.findById(dataSourceId)
                .orElseThrow(() -> new RuntimeException("Data source not found: " + dataSourceId));

        log.info("Executing SQL [dataSource={}]: {}", ds.getName(), sql);

        HikariDataSource pool = getPool(ds);

        try (Connection conn = pool.getConnection();
             java.sql.Statement stmt = conn.createStatement()) {

            stmt.setQueryTimeout(timeoutSeconds);
            stmt.setMaxRows(maxRows);

            try (ResultSet rs = stmt.executeQuery(sql)) {
                ResultSetMetaData metaData = rs.getMetaData();
                int columnCount = metaData.getColumnCount();

                List<String> columns = new ArrayList<>();
                for (int i = 1; i <= columnCount; i++) {
                    columns.add(metaData.getColumnLabel(i));
                }

                List<Map<String, Object>> rows = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= columnCount; i++) {
                        Object value = rs.getObject(i);
                        row.put(columns.get(i - 1), value);
                    }
                    rows.add(row);
                }

                log.info("SQL executed successfully, returned {} rows", rows.size());
                return SqlExecuteResult.builder()
                        .success(true)
                        .columns(columns)
                        .rows(rows)
                        .rowCount(rows.size())
                        .sql(sql)
                        .build();
            }

        } catch (SQLTimeoutException e) {
            log.error("SQL execution timeout: {}", sql);
            return SqlExecuteResult.builder()
                    .success(false)
                    .errorMessage("Query timeout (exceeded " + timeoutSeconds + "s), please optimize query")
                    .sql(sql)
                    .build();
        } catch (SQLException e) {
            log.error("SQL execution failed: {}, error: {}", sql, e.getMessage());
            return SqlExecuteResult.builder()
                    .success(false)
                    .errorMessage("SQL execution failed: " + e.getMessage())
                    .sql(sql)
                    .build();
        }
    }

    private void validateSql(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            throw new IllegalArgumentException("SQL cannot be empty");
        }

        if (allowOnlySelect) {
            try {
                Statement statement = CCJSqlParserUtil.parse(sql);
                if (!(statement instanceof Select)) {
                    throw new SecurityException("Security restriction: only SELECT queries allowed, not: " + statement.getClass().getSimpleName());
                }
            } catch (SecurityException e) {
                throw e;
            } catch (Exception e) {
                String upperSql = sql.trim().toUpperCase();
                List<String> dangerousKeywords = List.of("INSERT", "UPDATE", "DELETE", "DROP", "CREATE", "ALTER",
                        "TRUNCATE", "EXEC", "EXECUTE");
                for (String keyword : dangerousKeywords) {
                    // 使用单词边界匹配，避免标识符中的子串被误判
                    java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\b" + keyword + "\\b");
                    java.util.regex.Matcher matcher = pattern.matcher(upperSql);
                    if (matcher.find()) {
                        throw new SecurityException("Security restriction: SQL contains dangerous keyword " + keyword);
                    }
                }
            }
        }
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class SqlExecuteResult {
        private boolean success;
        private List<String> columns;
        private List<Map<String, Object>> rows;
        private int rowCount;
        private String sql;
        private String errorMessage;
    }
}
