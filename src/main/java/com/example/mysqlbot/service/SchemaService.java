package com.example.mysqlbot.service;

import com.example.mysqlbot.model.DataSource;
import com.example.mysqlbot.model.DatabaseDialect;
import com.example.mysqlbot.repository.DataSourceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Schema extraction and vectorization service.
 * Extracts table structure from target databases, vectorizes with embedding, and stores in pgvector.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SchemaService {

    private final DataSourceRepository dataSourceRepository;
    private final VectorStoreService vectorStoreService;

    private final java.util.Map<Long, SyncProgress> progressMap = new java.util.concurrent.ConcurrentHashMap<>();

    @lombok.Data
    public static class SyncProgress {
        private int totalTables;
        private int processedTables;
        private String currentTable;
        private boolean completed;
        private String error;
        private String status; // "extracting", "embedding", "done", "error"
    }

    public SyncProgress getSyncProgress(Long dataSourceId) {
        return progressMap.getOrDefault(dataSourceId, new SyncProgress());
    }

    public void syncSchema(Long dataSourceId) {
        SyncProgress progress = new SyncProgress();
        progress.setStatus("extracting");
        progressMap.put(dataSourceId, progress);

        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                doSyncSchema(dataSourceId, progress);
            } catch (Exception e) {
                log.error("Schema sync task failed", e);
                progress.setCompleted(true);
                progress.setStatus("error");
                progress.setError(e.getMessage());
            }
        });
    }

    private void doSyncSchema(Long dataSourceId, SyncProgress progress) {
        DataSource ds = dataSourceRepository.findById(dataSourceId)
                .orElseThrow(() -> new RuntimeException("Data source not found: " + dataSourceId));

        DatabaseDialect dialect = ds.getDialect();
        log.info("Starting schema sync for data source [{}] (type={})...", ds.getName(), dialect.getDisplayName());

        List<String> contentList = new ArrayList<>();
        List<Map<String, Object>> metaList = new ArrayList<>();

        try {
            try (Connection conn = DriverManager.getConnection(ds.buildJdbcUrl(), ds.getUsername(), ds.getPassword())) {
                DatabaseMetaData metaData = conn.getMetaData();

                try (ResultSet tables = metaData.getTables(ds.getDbName(), null, "%", new String[] { "TABLE" })) {
                    while (tables.next()) {
                        String tableName = tables.getString("TABLE_NAME");
                        String tableSchema = tables.getString("TABLE_SCHEM");
                        String tableComment = tables.getString("REMARKS");

                        // Build qualified table name with dialect-aware logic
                        String fullTableName = buildQualifiedTableName(dialect, tableSchema, tableName, ds.getDbName());

                        progress.setCurrentTable(fullTableName);

                        StringBuilder schemaText = new StringBuilder();
                        schemaText.append("Table: ").append(fullTableName).append("\n");
                        if (tableComment != null && !tableComment.isEmpty()) {
                            schemaText.append("Description: ").append(tableComment).append("\n");
                        }
                        schemaText.append("Columns:\n");

                        try (ResultSet columns = metaData.getColumns(ds.getDbName(), tableSchema, tableName, "%")) {
                            while (columns.next()) {
                                String colName = columns.getString("COLUMN_NAME");
                                String colType = columns.getString("TYPE_NAME");
                                String colComment = columns.getString("REMARKS");
                                String nullable = "YES".equals(columns.getString("IS_NULLABLE")) ? "nullable" : "not null";

                                schemaText.append("  - ").append(colName)
                                        .append(" (").append(colType).append(", ").append(nullable).append(")");
                                if (colComment != null && !colComment.isEmpty()) {
                                    schemaText.append(": ").append(colComment);
                                }
                                schemaText.append("\n");
                            }
                        }

                        List<String> primaryKeys = new ArrayList<>();
                        try (ResultSet pks = metaData.getPrimaryKeys(ds.getDbName(), tableSchema, tableName)) {
                            while (pks.next()) {
                                primaryKeys.add(pks.getString("COLUMN_NAME"));
                            }
                        }
                        if (!primaryKeys.isEmpty()) {
                            schemaText.append("Primary key: ").append(String.join(", ", primaryKeys)).append("\n");
                        }

                        // Sample data (top 5 rows)
                        schemaText.append("Sample data (top 5 rows):\n");
                        appendSampleData(conn, schemaText, fullTableName, dialect);

                        schemaText.append("\n");

                        metaList.add(Map.of("tableName", fullTableName));
                        contentList.add(schemaText.toString());

                        progress.setTotalTables(contentList.size());
                    }
                }
            }

            if (contentList.isEmpty()) {
                log.warn("Data source [{}] has no tables", ds.getName());
                progress.setCompleted(true);
                progress.setStatus("done");
                return;
            }

            progress.setStatus("embedding");
            progress.setProcessedTables(0);

            vectorStoreService.deleteByDataSourceAndType(dataSourceId, "schema");

            int batchSize = 10;
            for (int i = 0; i < contentList.size(); i += batchSize) {
                int end = Math.min(i + batchSize, contentList.size());
                List<String> subContent = contentList.subList(i, end);
                List<Map<String, Object>> subMeta = metaList.subList(i, end);

                progress.setCurrentTable("Embedding batch " + (i / batchSize + 1));
                vectorStoreService.addDocuments(subContent, dataSourceId, "schema", subMeta);

                progress.setProcessedTables(end);
            }

            DataSource updatedDs = dataSourceRepository.findById(dataSourceId).orElse(ds);
            updatedDs.setSchemaSyncedAt(LocalDateTime.now());
            dataSourceRepository.save(updatedDs);

            progress.setCompleted(true);
            progress.setStatus("done");
            log.info("Data source [{}] schema sync completed ({} tables)", ds.getName(), contentList.size());

        } catch (Exception e) {
            log.error("Schema sync failed", e);
            throw new RuntimeException("Sync failed: " + e.getMessage(), e);
        }
    }

    /**
     * Build a qualified table name with dialect-aware quoting.
     * MySQL typically uses a single schema (the database name), so we skip the schema prefix
     * when tableSchema is null, empty, or equals the database name.
     */
    private String buildQualifiedTableName(DatabaseDialect dialect, String tableSchema, String tableName, String dbName) {
        boolean useSchemaPrefix = tableSchema != null && !tableSchema.isBlank()
                && !tableSchema.equalsIgnoreCase(dbName);
        if (useSchemaPrefix) {
            return dialect.quoteQualifiedTable(tableSchema, tableName);
        }
        return tableName;
    }

    /**
     * Query sample data from a table and append as a Markdown table to the schema text.
     */
    private void appendSampleData(Connection conn, StringBuilder schemaText, String fullTableName, DatabaseDialect dialect) {
        try (java.sql.Statement stmt = conn.createStatement()) {
            stmt.setMaxRows(5);
            // Use quoted table name for safety
            String quotedName = fullTableName.contains(".") ? fullTableName : dialect.quoteIdentifier(fullTableName);
            try (ResultSet rs = stmt.executeQuery("SELECT * FROM " + quotedName)) {
                java.sql.ResultSetMetaData rsmd = rs.getMetaData();
                int columnCount = rsmd.getColumnCount();

                List<String> headers = new ArrayList<>();
                for (int i = 1; i <= columnCount; i++) {
                    headers.add(rsmd.getColumnName(i));
                }
                schemaText.append("| ").append(String.join(" | ", headers)).append(" |\n");

                List<String> separators = new ArrayList<>();
                for (int i = 1; i <= columnCount; i++) {
                    separators.add("---");
                }
                schemaText.append("| ").append(String.join(" | ", separators)).append(" |\n");

                int rowCount = 0;
                while (rs.next()) {
                    rowCount++;
                    List<String> rowValues = new ArrayList<>();
                    for (int i = 1; i <= columnCount; i++) {
                        Object val = rs.getObject(i);
                        String valStr = (val == null) ? "NULL" : val.toString();
                        if (valStr.length() > 50) {
                            valStr = valStr.substring(0, 47) + "...";
                        }
                        valStr = valStr.replace("\n", " ").replace("\r", "").replace("|", "\\|");
                        rowValues.add(valStr);
                    }
                    schemaText.append("| ").append(String.join(" | ", rowValues)).append(" |\n");
                }
                if (rowCount == 0) {
                    schemaText.append("(empty table)\n");
                }
            }
        } catch (Exception e) {
            log.warn("Cannot get sample data for table [{}]: {}", fullTableName, e.getMessage());
            schemaText.append("(no permission or unable to fetch sample data)\n");
        }
    }

    public boolean testConnection(DataSource ds) {
        try (Connection conn = DriverManager.getConnection(ds.buildJdbcUrl(), ds.getUsername(), ds.getPassword())) {
            return conn.isValid(5);
        } catch (Exception e) {
            log.error("Data source connection test failed: {}", e.getMessage());
            return false;
        }
    }
}
