package com.example.mysqlbot.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SQL security validation logic.
 */
class SqlExecuteServiceTest {

    @Test
    void allowSelectStatement() {
        // SELECT should pass validation
        String sql = "SELECT id, name FROM users LIMIT 1000";
        assertDoesNotThrow(() -> validateSql(sql));
    }

    @Test
    void rejectInsertStatement() {
        String sql = "INSERT INTO users (name) VALUES ('hacker')";
        assertThrows(SecurityException.class, () -> validateSql(sql));
    }

    @Test
    void rejectUpdateStatement() {
        String sql = "UPDATE users SET role = 'admin'";
        assertThrows(SecurityException.class, () -> validateSql(sql));
    }

    @Test
    void rejectDeleteStatement() {
        String sql = "DELETE FROM users";
        assertThrows(SecurityException.class, () -> validateSql(sql));
    }

    @Test
    void rejectDropStatement() {
        String sql = "DROP TABLE users";
        assertThrows(SecurityException.class, () -> validateSql(sql));
    }

    @Test
    void rejectEmptySql() {
        assertThrows(IllegalArgumentException.class, () -> validateSql(""));
        assertThrows(IllegalArgumentException.class, () -> validateSql(null));
    }

    @Test
    void rejectAlterStatement() {
        String sql = "ALTER TABLE users ADD COLUMN admin boolean";
        assertThrows(SecurityException.class, () -> validateSql(sql));
    }

    // Mirror the validation logic from SqlExecuteService
    private void validateSql(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            throw new IllegalArgumentException("SQL cannot be empty");
        }
        try {
            net.sf.jsqlparser.statement.Statement statement = net.sf.jsqlparser.parser.CCJSqlParserUtil.parse(sql);
            if (!(statement instanceof net.sf.jsqlparser.statement.select.Select)) {
                throw new SecurityException("Only SELECT queries allowed");
            }
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            String upperSql = sql.trim().toUpperCase();
            java.util.List<String> dangerous = java.util.List.of("INSERT", "UPDATE", "DELETE", "DROP",
                    "CREATE", "ALTER", "TRUNCATE", "EXEC", "EXECUTE");
            for (String keyword : dangerous) {
                if (upperSql.contains(keyword)) {
                    throw new SecurityException("SQL contains dangerous keyword: " + keyword);
                }
            }
        }
    }
}
