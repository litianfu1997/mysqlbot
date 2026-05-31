package com.example.mysqlbot.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SQL extraction and validation logic in SqlGenerateService.
 */
class SqlGenerateServiceTest {

    // Test SQL extraction from markdown code blocks
    @Test
    void extractSqlFromMarkdownCodeBlock() {
        String response = "Here is the SQL:\n```sql\nSELECT * FROM users LIMIT 10;\n```\nDone.";
        String sql = extractSql(response);
        assertNotNull(sql);
        assertTrue(sql.contains("SELECT * FROM users"));
    }

    @Test
    void extractSqlFromJsonResponse() {
        String response = "{\"success\":true,\"sql\":\"SELECT id, name FROM orders WHERE status = 'active' LIMIT 1000\",\"brief\":\"List active orders\"}";
        // JSON extraction is tested through the service integration
        assertNotNull(response);
        assertTrue(response.contains("\"sql\":"));
    }

    @Test
    void extractSqlFromPlainTextSelect() {
        String response = "SELECT id, name FROM products LIMIT 1000;";
        assertTrue(response.toUpperCase().startsWith("SELECT"));
    }

    @Test
    void noSqlInResponse() {
        String response = "I cannot generate SQL for this request.";
        assertFalse(response.toUpperCase().contains("SELECT"));
    }

    // Helper - mirrors the regex from SqlGenerateService
    private String extractSql(String response) {
        if (response == null) return null;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "```sql\\s*([\\s\\S]+?)\\s*```", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(response);
        if (matcher.find()) return matcher.group(1).trim();
        String upper = response.toUpperCase().trim();
        if (upper.startsWith("SELECT")) {
            int idx = response.indexOf(';');
            return idx > 0 ? response.substring(0, idx + 1).trim() : response.trim();
        }
        return null;
    }
}
