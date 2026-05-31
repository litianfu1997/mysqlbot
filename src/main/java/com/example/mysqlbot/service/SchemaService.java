package com.example.mysqlbot.service;

import com.example.mysqlbot.model.DataSource;
import com.example.mysqlbot.model.DatabaseDialect;
import com.example.mysqlbot.model.TableRelation;
import com.example.mysqlbot.repository.DataSourceRepository;
import com.example.mysqlbot.repository.TableRelationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Schema extraction and relation inference service.
 * Extracts table structure (columns, PKs, FKs) and infers/persists table relations.
 * Relations are consumed by the get_table_relations tool during LLM-driven SQL generation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SchemaService {

    private final DataSourceRepository dataSourceRepository;
    private final RelationInferenceService relationInferenceService;
    private final TableRelationRepository tableRelationRepository;
    private final LlmService llmService;

    private final java.util.Map<Long, SyncProgress> progressMap = new java.util.concurrent.ConcurrentHashMap<>();

    @lombok.Data
    public static class SyncProgress {
        private int totalTables;
        private int processedTables;
        private String currentTable;
        private boolean completed;
        private String error;
        private String status; // "extracting", "done", "error"
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

        // Pass 1: collect TableMeta for relation inference
        List<RelationInferenceService.TableMeta> tableMetas = new ArrayList<>();

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

                        List<String> columnNames = new ArrayList<>();
                        try (ResultSet columns = metaData.getColumns(ds.getDbName(), tableSchema, tableName, "%")) {
                            while (columns.next()) {
                                columnNames.add(columns.getString("COLUMN_NAME"));
                            }
                        }

                        List<String> primaryKeys = new ArrayList<>();
                        try (ResultSet pks = metaData.getPrimaryKeys(ds.getDbName(), tableSchema, tableName)) {
                            while (pks.next()) {
                                primaryKeys.add(pks.getString("COLUMN_NAME"));
                            }
                        }

                        progress.setTotalTables(tableMetas.size() + 1);

                        // Build TableMeta for relation inference
                        List<RelationInferenceService.ForeignKeyInfo> fkList =
                                extractImportedKeys(metaData, ds, dialect, tableSchema, tableName, fullTableName);
                        RelationInferenceService.TableMeta meta = new RelationInferenceService.TableMeta();
                        meta.tableName = fullTableName;
                        meta.simpleTableName = tableName;
                        meta.columns = columnNames;
                        meta.primaryKeys = primaryKeys;
                        meta.importedKeys = fkList;
                        tableMetas.add(meta);
                    }
                }
            }

            if (tableMetas.isEmpty()) {
                log.warn("Data source [{}] has no tables", ds.getName());
                tableRelationRepository.safelyDeleteByDataSourceIdAndSourceIn(
                        dataSourceId, List.of("fk", "naming", "llm"));
                progress.setCompleted(true);
                progress.setStatus("done");
                return;
            }

            // Pass 2: infer and persist relations (connection already closed)
            try {
                inferAndSaveRelations(dataSourceId, tableMetas, dialect.getDisplayName());
            } catch (Exception e) {
                log.warn("Relation inference failed for dataSourceId={}, continuing schema sync. Reason: {}",
                        dataSourceId, e.getMessage());
            }

            DataSource updatedDs = dataSourceRepository.findById(dataSourceId).orElse(ds);
            updatedDs.setSchemaSyncedAt(LocalDateTime.now());
            dataSourceRepository.save(updatedDs);

            progress.setCompleted(true);
            progress.setStatus("done");
            log.info("Data source [{}] schema sync completed ({} tables, relations inferred)", ds.getName(), tableMetas.size());

        } catch (Exception e) {
            log.error("Schema sync failed", e);
            throw new RuntimeException("Sync failed: " + e.getMessage(), e);
        }
    }

    /**
     * Reads physical foreign-key constraints for a single table via JDBC metadata.
     */
    private List<RelationInferenceService.ForeignKeyInfo> extractImportedKeys(
            DatabaseMetaData metaData, DataSource ds, DatabaseDialect dialect,
            String tableSchema, String tableName, String fullTableName) {
        List<RelationInferenceService.ForeignKeyInfo> result = new ArrayList<>();
        try (ResultSet fks = metaData.getImportedKeys(ds.getDbName(), tableSchema, tableName)) {
            while (fks.next()) {
                String fkColumn      = fks.getString("FKCOLUMN_NAME");
                String pkTableName   = fks.getString("PKTABLE_NAME");
                String pkTableSchema = fks.getString("PKTABLE_SCHEM");
                String pkColumn      = fks.getString("PKCOLUMN_NAME");
                String pkFullTable   = buildQualifiedTableName(dialect, pkTableSchema, pkTableName, ds.getDbName());
                result.add(new RelationInferenceService.ForeignKeyInfo(fkColumn, pkFullTable, pkColumn));
            }
        } catch (Exception e) {
            log.warn("Cannot extract foreign keys for table [{}]: {}", fullTableName, e.getMessage());
        }
        return result;
    }

    /**
     * Runs all three inference strategies and persists the deduplicated results.
     * Manual relations are never touched.
     * <p>
     * {@code @Transactional} ensures that the delete and saveAll happen in the same
     * transaction — if saveAll throws, the delete is rolled back too.
     */
    @Transactional
    void inferAndSaveRelations(Long dataSourceId,
                               List<RelationInferenceService.TableMeta> tableMetas,
                               String dbEngine) {
        // 1. Remove previously auto-inferred relations (fk / naming / llm); keep manual
        tableRelationRepository.safelyDeleteByDataSourceIdAndSourceIn(
                dataSourceId, List.of("fk", "naming", "llm"));

        // 2. Run all three strategies
        List<RelationInferenceService.InferredRelation> all = new ArrayList<>();
        all.addAll(relationInferenceService.inferFromForeignKeys(tableMetas));
        all.addAll(relationInferenceService.inferFromNamingConventions(tableMetas));
        all.addAll(relationInferenceService.inferFromLlm(tableMetas, llmService, dbEngine));

        // 3. Deduplicate: fk wins over llm wins over naming (insert fk first, putIfAbsent keeps first)
        Map<String, RelationInferenceService.InferredRelation> dedup = new LinkedHashMap<>();
        for (String src : List.of("fk", "llm", "naming")) {
            for (RelationInferenceService.InferredRelation r : all) {
                if (!src.equals(r.getSource())) continue;
                String key = r.getFromTable() + "|" + r.getFromColumn()
                        + "|" + r.getToTable() + "|" + r.getToColumn();
                dedup.putIfAbsent(key, r);
            }
        }

        // 4. Exclude keys already covered by manual relations
        Set<String> manualKeys = tableRelationRepository
                .findByDataSourceIdAndIsActive(dataSourceId, 1)
                .stream()
                .filter(r -> "manual".equals(r.getSource()))
                .map(r -> r.getFromTable() + "|" + r.getFromColumn()
                        + "|" + r.getToTable() + "|" + r.getToColumn())
                .collect(Collectors.toSet());

        List<TableRelation> toSave = dedup.values().stream()
                .filter(r -> !manualKeys.contains(
                        r.getFromTable() + "|" + r.getFromColumn()
                                + "|" + r.getToTable() + "|" + r.getToColumn()))
                .map(r -> TableRelation.builder()
                        .dataSourceId(dataSourceId)
                        .fromTable(r.getFromTable())
                        .fromColumn(r.getFromColumn())
                        .toTable(r.getToTable())
                        .toColumn(r.getToColumn())
                        .source(r.getSource())
                        .confidence(r.getConfidence())
                        .isActive(1)
                        .build())
                .collect(Collectors.toList());

        tableRelationRepository.saveAll(toSave);
        log.info("Table relation inference completed: {} relations saved (dataSourceId={})",
                toSave.size(), dataSourceId);
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

    public boolean testConnection(DataSource ds) {
        try (Connection conn = DriverManager.getConnection(ds.buildJdbcUrl(), ds.getUsername(), ds.getPassword())) {
            return conn.isValid(5);
        } catch (Exception e) {
            log.error("Data source connection test failed: {}", e.getMessage());
            return false;
        }
    }
}
