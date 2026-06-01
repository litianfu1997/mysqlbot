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

        try {
            // Pass 1: collect TableMeta (columns, PKs, FKs) for relation inference
            List<RelationInferenceService.TableMeta> tableMetas = extractTableMetas(ds, progress);

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
     * Connects to the data source and collects {@link RelationInferenceService.TableMeta}
     * (columns, primary keys, imported foreign keys) for every table. Shared by schema
     * sync, AI relation generation, and the schema-tables dropdown endpoint.
     *
     * @param progress optional sync-progress sink; pass {@code null} when not syncing.
     */
    private List<RelationInferenceService.TableMeta> extractTableMetas(DataSource ds, SyncProgress progress) throws Exception {
        DatabaseDialect dialect = ds.getDialect();
        List<RelationInferenceService.TableMeta> tableMetas = new ArrayList<>();

        try (Connection conn = DriverManager.getConnection(ds.buildJdbcUrl(), ds.getUsername(), ds.getPassword())) {
            DatabaseMetaData metaData = conn.getMetaData();

            try (ResultSet tables = metaData.getTables(ds.getDbName(), null, "%", new String[] { "TABLE" })) {
                while (tables.next()) {
                    String tableName = tables.getString("TABLE_NAME");
                    String tableSchema = tables.getString("TABLE_SCHEM");

                    // Build qualified table name with dialect-aware logic
                    String fullTableName = buildQualifiedTableName(dialect, tableSchema, tableName, ds.getDbName());

                    if (progress != null) progress.setCurrentTable(fullTableName);

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

                    if (progress != null) progress.setTotalTables(tableMetas.size() + 1);

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
        return tableMetas;
    }

    /**
     * Runs LLM-only relation inference for the given data source and returns the
     * candidate relations WITHOUT persisting them. Candidates whose key already
     * exists (any source) are filtered out so the preview only shows new relations.
     */
    public List<TableRelation> previewLlmRelations(Long dataSourceId) throws Exception {
        DataSource ds = dataSourceRepository.findById(dataSourceId)
                .orElseThrow(() -> new RuntimeException("Data source not found: " + dataSourceId));

        List<RelationInferenceService.TableMeta> tableMetas = extractTableMetas(ds, null);
        if (tableMetas.isEmpty()) return new ArrayList<>();

        List<RelationInferenceService.InferredRelation> inferred =
                relationInferenceService.inferFromLlm(tableMetas, llmService, ds.getDialect().getDisplayName());

        Set<String> existingKeys = tableRelationRepository.findByDataSourceIdAndIsActive(dataSourceId, 1)
                .stream().map(this::relationKey).collect(Collectors.toSet());

        List<TableRelation> candidates = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (RelationInferenceService.InferredRelation r : inferred) {
            String key = r.getFromTable() + "|" + r.getFromColumn() + "|" + r.getToTable() + "|" + r.getToColumn();
            if (existingKeys.contains(key) || !seen.add(key)) continue;
            candidates.add(TableRelation.builder()
                    .dataSourceId(dataSourceId)
                    .fromTable(r.getFromTable())
                    .fromColumn(r.getFromColumn())
                    .toTable(r.getToTable())
                    .toColumn(r.getToColumn())
                    .source("llm")
                    .confidence(r.getConfidence())
                    .isActive(1)
                    .build());
        }
        log.info("AI relation preview for dataSourceId={}: {} new candidate(s)", dataSourceId, candidates.size());
        return candidates;
    }

    /**
     * Persists the user-selected AI candidates as source="llm" relations (append-only).
     * Candidates duplicating an existing relation (any source) are skipped to respect
     * the unique constraint.
     */
    @Transactional
    public List<TableRelation> saveSelectedLlmRelations(Long dataSourceId, List<TableRelation> selected) {
        if (selected == null || selected.isEmpty()) return new ArrayList<>();

        Set<String> existingKeys = tableRelationRepository.findByDataSourceIdAndIsActive(dataSourceId, 1)
                .stream().map(this::relationKey).collect(Collectors.toSet());

        List<TableRelation> toSave = new ArrayList<>();
        for (TableRelation r : selected) {
            if (r.getFromTable() == null || r.getFromColumn() == null
                    || r.getToTable() == null || r.getToColumn() == null) continue;
            String key = relationKey(r);
            if (!existingKeys.add(key)) continue; // skip duplicates (existing + within-batch)
            toSave.add(TableRelation.builder()
                    .dataSourceId(dataSourceId)
                    .fromTable(r.getFromTable())
                    .fromColumn(r.getFromColumn())
                    .toTable(r.getToTable())
                    .toColumn(r.getToColumn())
                    .source("llm")
                    .confidence(r.getConfidence())
                    .isActive(1)
                    .build());
        }
        List<TableRelation> saved = tableRelationRepository.saveAll(toSave);
        log.info("Saved {} AI-generated relation(s) for dataSourceId={}", saved.size(), dataSourceId);
        return saved;
    }

    /**
     * Returns every table with its column list (fully-qualified table names),
     * used to drive the manual relation form's dropdowns.
     */
    public List<Map<String, Object>> listSchemaTables(Long dataSourceId) throws Exception {
        DataSource ds = dataSourceRepository.findById(dataSourceId)
                .orElseThrow(() -> new RuntimeException("Data source not found: " + dataSourceId));
        List<RelationInferenceService.TableMeta> metas = extractTableMetas(ds, null);
        List<Map<String, Object>> result = new ArrayList<>();
        for (RelationInferenceService.TableMeta m : metas) {
            result.add(Map.of("table", m.tableName, "columns", m.columns));
        }
        return result;
    }

    private String relationKey(TableRelation r) {
        return r.getFromTable() + "|" + r.getFromColumn() + "|" + r.getToTable() + "|" + r.getToColumn();
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
        // Skip the schema prefix for the default schema:
        //  - MySQL: the schema equals the database name
        //  - PostgreSQL: the default "public" schema is on the search_path, so tables are
        //    reachable unqualified. Keeping it bare also matches listTableNames() / the tool
        //    layer, which already use bare table names.
        // Non-default schemas (e.g. "sales") are still qualified to avoid ambiguity.
        boolean useSchemaPrefix = tableSchema != null && !tableSchema.isBlank()
                && !tableSchema.equalsIgnoreCase(dbName)
                && !"public".equalsIgnoreCase(tableSchema);
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
