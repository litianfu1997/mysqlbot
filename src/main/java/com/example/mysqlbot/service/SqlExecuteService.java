package com.example.mysqlbot.service;

import com.example.mysqlbot.model.DataSource;
import com.example.mysqlbot.repository.DataSourceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Select;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;

/**
* SQL execution service with connection pooling per DataSource.
 * SQL execution service. Connection pooling is delegated to {@link ConnectionPoolService}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SqlExecuteService {

    private final DataSourceRepository dataSourceRepository;
    private final ConnectionPoolService connectionPoolService;

    @Value("${mysqlbot.sql.allow-only-select:true}")
    private boolean allowOnlySelect;

    @Value("${mysqlbot.sql.max-rows:1000}")
    private int maxRows;

    @Value("${mysqlbot.sql.timeout-seconds:30}")
    private int timeoutSeconds;

    private static final Pattern[] DANGEROUS_KEYWORD_PATTERNS;
    static {
        List<String> keywords = List.of("INSERT", "UPDATE", "DELETE", "DROP", "CREATE", "ALTER", "TRUNCATE", "EXEC", "EXECUTE");
        DANGEROUS_KEYWORD_PATTERNS = keywords.stream()
                .map(k -> java.util.regex.Pattern.compile("\\b" + k + "\\b", java.util.regex.Pattern.CASE_INSENSITIVE))
                .toArray(java.util.regex.Pattern[]::new);
    }

    public SqlExecuteResult execute(String sql, Long dataSourceId) {
        validateSql(sql);

        DataSource ds = dataSourceRepository.findById(dataSourceId)
                .orElseThrow(() -> new RuntimeException("Data source not found: " + dataSourceId));

        log.info("Executing SQL [dataSource={}]: {}", ds.getName(), sql);

        try (Connection conn = connectionPoolService.getConnection(ds);
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
                for (java.util.regex.Pattern pattern : DANGEROUS_KEYWORD_PATTERNS) {
                    if (pattern.matcher(upperSql).find()) {
                        throw new SecurityException("Security restriction: SQL contains dangerous keyword");
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
