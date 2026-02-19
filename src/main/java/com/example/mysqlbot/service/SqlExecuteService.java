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

/**
 * SQL 执行服务
 * 安全地执行 LLM 生成的 SQL 并返回结果
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SqlExecuteService {

    private final DataSourceRepository dataSourceRepository;

    @Value("${mysqlbot.sql.allow-only-select:true}")
    private boolean allowOnlySelect;

    @Value("${mysqlbot.sql.max-rows:1000}")
    private int maxRows;

    @Value("${mysqlbot.sql.timeout-seconds:30}")
    private int timeoutSeconds;

    /**
     * 执行 SQL 并返回结果
     *
     * @param sql          要执行的 SQL
     * @param dataSourceId 目标数据源 ID
     * @return 执行结果
     */
    public SqlExecuteResult execute(String sql, Long dataSourceId) {
        // 1. 安全验证
        validateSql(sql);

        DataSource ds = dataSourceRepository.findById(dataSourceId)
                .orElseThrow(() -> new RuntimeException("数据源不存在: " + dataSourceId));

        log.info("执行 SQL [dataSource={}]: {}", ds.getName(), sql);

        // 2. 执行查询
        try (Connection conn = DriverManager.getConnection(ds.buildJdbcUrl(), ds.getUsername(), ds.getPassword());
                java.sql.Statement stmt = conn.createStatement()) {

            stmt.setQueryTimeout(timeoutSeconds);
            stmt.setMaxRows(maxRows);

            try (ResultSet rs = stmt.executeQuery(sql)) {
                ResultSetMetaData metaData = rs.getMetaData();
                int columnCount = metaData.getColumnCount();

                // 提取列名
                List<String> columns = new ArrayList<>();
                for (int i = 1; i <= columnCount; i++) {
                    columns.add(metaData.getColumnLabel(i));
                }

                // 提取数据行
                List<Map<String, Object>> rows = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= columnCount; i++) {
                        Object value = rs.getObject(i);
                        row.put(columns.get(i - 1), value);
                    }
                    rows.add(row);
                }

                log.info("SQL 执行成功，返回 {} 行数据", rows.size());
                return SqlExecuteResult.builder()
                        .success(true)
                        .columns(columns)
                        .rows(rows)
                        .rowCount(rows.size())
                        .sql(sql)
                        .build();
            }

        } catch (SQLTimeoutException e) {
            log.error("SQL 执行超时: {}", sql);
            return SqlExecuteResult.builder()
                    .success(false)
                    .errorMessage("查询超时（超过 " + timeoutSeconds + " 秒），请优化查询条件")
                    .sql(sql)
                    .build();
        } catch (SQLException e) {
            log.error("SQL 执行失败: {}, 错误: {}", sql, e.getMessage());
            return SqlExecuteResult.builder()
                    .success(false)
                    .errorMessage("SQL 执行失败: " + e.getMessage())
                    .sql(sql)
                    .build();
        }
    }

    /**
     * SQL 安全验证
     * 使用 JSqlParser 解析 SQL，确保只允许 SELECT 语句
     */
    private void validateSql(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            throw new IllegalArgumentException("SQL 不能为空");
        }

        if (allowOnlySelect) {
            try {
                Statement statement = CCJSqlParserUtil.parse(sql);
                if (!(statement instanceof Select)) {
                    throw new SecurityException("安全限制：只允许执行 SELECT 查询，不允许执行: " + statement.getClass().getSimpleName());
                }
            } catch (SecurityException e) {
                throw e;
            } catch (Exception e) {
                // 解析失败时，做简单的关键字检查
                String upperSql = sql.trim().toUpperCase();
                List<String> dangerousKeywords = List.of("INSERT", "UPDATE", "DELETE", "DROP", "CREATE", "ALTER",
                        "TRUNCATE", "EXEC", "EXECUTE");
                for (String keyword : dangerousKeywords) {
                    if (upperSql.contains(keyword)) {
                        throw new SecurityException("安全限制：SQL 包含危险关键字: " + keyword);
                    }
                }
            }
        }
    }

    /**
     * SQL 执行结果
     */
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
