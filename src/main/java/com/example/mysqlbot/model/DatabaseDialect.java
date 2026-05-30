package com.example.mysqlbot.model;

import java.util.Arrays;

/**
 * Database dialect abstraction.
 * Centralizes per-database differences: JDBC URL format, identifier quoting, default port.
 * Add new databases by adding an enum constant.
 */
public enum DatabaseDialect {

    POSTGRESQL(
            "postgresql",
            "PostgreSQL",
            '"',
            5432,
            "jdbc:postgresql://%s:%d/%s",
            """
            Identifiers are case-insensitive and folded to lowercase. Use double quotes (") only for:
            - PG reserved words (user, order, group, desc, select, etc.)
            - Identifiers with special characters
            - When case must be preserved
            Dots (.) must NOT be inside double quotes; write "schema"."table" not "schema.table".
            Backticks (`) are ILLEGAL in PostgreSQL.
            """,
            "postgresql"
    ),

    MYSQL(
            "mysql",
            "MySQL",
            '`',
            3306,
            "jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&remarks=true",
            """
            Identifiers use backticks (`) for quoting.
            Double quotes (") are for string literals by default (unless ANSI_QUOTES SQL mode is set).
            Use backticks for reserved words, special characters, or when case must be preserved.
            """,
            "mysql"
    );

    private final String id;
    private final String displayName;
    private final char quoteChar;
    private final int defaultPort;
    private final String jdbcUrlTemplate;
    private final String quotingRules;
    private final String driverClassName;

    DatabaseDialect(String id, String displayName, char quoteChar, int defaultPort,
                    String jdbcUrlTemplate, String quotingRules, String driverClassName) {
        this.id = id;
        this.displayName = displayName;
        this.quoteChar = quoteChar;
        this.defaultPort = defaultPort;
        this.jdbcUrlTemplate = jdbcUrlTemplate;
        this.quotingRules = quotingRules;
        this.driverClassName = driverClassName;
    }

    public String getId() { return id; }
    public String getDisplayName() { return displayName; }
    public char getQuoteChar() { return quoteChar; }
    public int getDefaultPort() { return defaultPort; }
    public String getQuotingRules() { return quotingRules; }
    public String getDriverClassName() { return driverClassName; }

    public String buildJdbcUrl(String host, int port, String dbName) {
        return String.format(jdbcUrlTemplate, host, port, dbName);
    }

    /**
     * Quote an identifier (table name, column name) with the dialect-appropriate quote character.
     * e.g. PostgreSQL: "table_name"  MySQL: `table_name`
     */
    public String quoteIdentifier(String identifier) {
        return quoteChar + identifier + quoteChar;
    }

    /**
     * Quote a qualified table name (schema.table or just table).
     */
    public String quoteQualifiedTable(String schema, String table) {
        if (schema != null && !schema.isBlank()) {
            return quoteIdentifier(schema) + "." + quoteIdentifier(table);
        }
        return quoteIdentifier(table);
    }

    /**
     * Look up dialect by dbType string. Throws if unknown.
     */
    public static DatabaseDialect fromDbType(String dbType) {
        if (dbType == null) throw new IllegalArgumentException("dbType must not be null");
        return Arrays.stream(values())
                .filter(d -> d.id.equalsIgnoreCase(dbType))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unsupported database type: " + dbType +
                        ". Supported types: " + Arrays.stream(values()).map(d -> d.id).toList()));
    }
}
