package com.example.mysqlbot.service;

import com.example.mysqlbot.model.DataSource;
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
 * Schema 提取与向量化服务
 * 从目标数据库提取表结构，使用智谱 embedding-3 向量化后存入 pgvector
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

    /**
     * 异步同步指定数据源的 Schema 到向量数据库
     */
    public void syncSchema(Long dataSourceId) {
        SyncProgress progress = new SyncProgress();
        progress.setStatus("extracting");
        progressMap.put(dataSourceId, progress);

        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                doSyncSchema(dataSourceId, progress);
            } catch (Exception e) {
                log.error("同步数据源任务异常", e);
                progress.setCompleted(true);
                progress.setStatus("error");
                progress.setError(e.getMessage());
            }
        });
    }

    private void doSyncSchema(Long dataSourceId, SyncProgress progress) {
        DataSource ds = dataSourceRepository.findById(dataSourceId)
                .orElseThrow(() -> new RuntimeException("数据源不存在: " + dataSourceId));

        log.info("开始同步数据源 [{}] 的 Schema...", ds.getName());

        List<String> contentList = new ArrayList<>();
        List<Map<String, Object>> metaList = new ArrayList<>();

        try {
            try (Connection conn = DriverManager.getConnection(ds.buildJdbcUrl(), ds.getUsername(), ds.getPassword())) {
                DatabaseMetaData metaData = conn.getMetaData();

                // 获取所有表
                try (ResultSet tables = metaData.getTables(ds.getDbName(), null, "%", new String[] { "TABLE" })) {
                    // count total tables roughly (not exactly possible with fast next() but we can
                    // collect them)
                    // actually we will just extract schema text here
                    while (tables.next()) {
                        String tableName = tables.getString("TABLE_NAME");
                        String tableSchema = tables.getString("TABLE_SCHEM");
                        String tableComment = tables.getString("REMARKS");

                        String fullTableName = tableName;
                        if (tableSchema != null && !tableSchema.isBlank()) {
                            fullTableName = tableSchema + "." + tableName;
                        } else if ("mysql".equalsIgnoreCase(ds.getDbType())) {
                            fullTableName = ds.getDbName() + "." + tableName;
                            tableSchema = null;
                        }

                        progress.setCurrentTable(fullTableName); // 更新当前正在提取的表名

                        StringBuilder schemaText = new StringBuilder();
                        schemaText.append("表名: ").append(fullTableName).append("\n");
                        if (tableComment != null && !tableComment.isEmpty()) {
                            schemaText.append("表说明: ").append(tableComment).append("\n");
                        }
                        schemaText.append("字段列表:\n");

                        try (ResultSet columns = metaData.getColumns(ds.getDbName(), tableSchema, tableName, "%")) {
                            while (columns.next()) {
                                String colName = columns.getString("COLUMN_NAME");
                                String colType = columns.getString("TYPE_NAME");
                                String colComment = columns.getString("REMARKS");
                                String nullable = "YES".equals(columns.getString("IS_NULLABLE")) ? "可空" : "非空";

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
                            schemaText.append("主键: ").append(String.join(", ", primaryKeys)).append("\n");
                        }

                        // 新增：获取前 5 条数据示例
                        schemaText.append("数据示例 (前5条):\n");
                        try (java.sql.Statement stmt = conn.createStatement()) {
                            stmt.setMaxRows(5);
                            try (ResultSet rs = stmt.executeQuery("SELECT * FROM " + fullTableName)) {
                                java.sql.ResultSetMetaData rsmd = rs.getMetaData();
                                int columnCount = rsmd.getColumnCount();

                                // 追加表头
                                List<String> headers = new ArrayList<>();
                                for (int i = 1; i <= columnCount; i++) {
                                    headers.add(rsmd.getColumnName(i));
                                }
                                schemaText.append("| ").append(String.join(" | ", headers)).append(" |\n");

                                // 追加 Markdown 分隔线
                                List<String> separators = new ArrayList<>();
                                for (int i = 1; i <= columnCount; i++) {
                                    separators.add("---");
                                }
                                schemaText.append("| ").append(String.join(" | ", separators)).append(" |\n");

                                // 追加数据行
                                int rowCount = 0;
                                while (rs.next()) {
                                    rowCount++;
                                    List<String> rowValues = new ArrayList<>();
                                    for (int i = 1; i <= columnCount; i++) {
                                        Object val = rs.getObject(i);
                                        String valStr = (val == null) ? "NULL" : val.toString();
                                        // 截断太长的数据
                                        if (valStr.length() > 50) {
                                            valStr = valStr.substring(0, 47) + "...";
                                        }
                                        // 替换掉换行符，防止破坏格式，转义管道符
                                        valStr = valStr.replace("\n", " ").replace("\r", "").replace("|", "\\|");
                                        rowValues.add(valStr);
                                    }
                                    schemaText.append("| ").append(String.join(" | ", rowValues)).append(" |\n");
                                }
                                if (rowCount == 0) {
                                    schemaText.append("(空表)\n");
                                }
                            }
                        } catch (Exception e) {
                            log.warn("无法获取表 [{}] 的示例数据: {}", fullTableName, e.getMessage());
                            schemaText.append("(无权限或无法获取示例数据)\n");
                        }
                        schemaText.append("\n");

                        metaList.add(Map.of("tableName", fullTableName));
                        contentList.add(schemaText.toString());

                        progress.setTotalTables(contentList.size()); // 提取阶段仅仅增加总数
                    }
                }
            } // 关闭目标数据库连接

            if (contentList.isEmpty()) {
                log.warn("数据源 [{}] 未提取到任何表结构", ds.getName());
                progress.setCompleted(true);
                progress.setStatus("done");
                return;
            }

            progress.setStatus("embedding");
            progress.setProcessedTables(0);

            // 先删除旧向量
            vectorStoreService.deleteByDataSourceAndType(dataSourceId, "schema");

            // 分批写入，更新进度
            int batchSize = 10;
            for (int i = 0; i < contentList.size(); i += batchSize) {
                int end = Math.min(i + batchSize, contentList.size());
                List<String> subContent = contentList.subList(i, end);
                List<Map<String, Object>> subMeta = metaList.subList(i, end);

                progress.setCurrentTable("向量化批次 " + (i / batchSize + 1));
                vectorStoreService.addDocuments(subContent, dataSourceId, "schema", subMeta);

                progress.setProcessedTables(end);
            }

            DataSource updatedDs = dataSourceRepository.findById(dataSourceId).orElse(ds);
            updatedDs.setSchemaSyncedAt(LocalDateTime.now());
            dataSourceRepository.save(updatedDs);

            progress.setCompleted(true);
            progress.setStatus("done");
            log.info("数据源 [{}] 同步彻底完成", ds.getName());

        } catch (Exception e) {
            log.error("获取或者写入 schema 失败", e);
            throw new RuntimeException("同步失败: " + e.getMessage(), e);
        }
    }

    /**
     * 测试数据源连接
     */
    public boolean testConnection(DataSource ds) {
        try (Connection conn = DriverManager.getConnection(ds.buildJdbcUrl(), ds.getUsername(), ds.getPassword())) {
            return conn.isValid(5);
        } catch (Exception e) {
            log.error("数据源连接测试失败: {}", e.getMessage());
            return false;
        }
    }
}
