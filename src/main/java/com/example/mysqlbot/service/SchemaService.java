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

    /**
     * 同步指定数据源的 Schema 到向量数据库
     */
    public void syncSchema(Long dataSourceId) {
        DataSource ds = dataSourceRepository.findById(dataSourceId)
                .orElseThrow(() -> new RuntimeException("数据源不存在: " + dataSourceId));

        log.info("开始同步数据源 [{}] 的 Schema...", ds.getName());

        List<String> contentList = new ArrayList<>();
        List<Map<String, Object>> metaList = new ArrayList<>();

        try (Connection conn = DriverManager.getConnection(ds.buildJdbcUrl(), ds.getUsername(), ds.getPassword())) {
            DatabaseMetaData metaData = conn.getMetaData();

            // 获取所有表
            // MySQL: catalog=dbName, schema=null
            // PostgreSQL: catalog=dbName, schemaPattern=null (获取所有 schema)
            try (ResultSet tables = metaData.getTables(ds.getDbName(), null, "%", new String[] { "TABLE" })) {
                while (tables.next()) {
                    String tableName = tables.getString("TABLE_NAME");
                    String tableSchema = tables.getString("TABLE_SCHEM"); // MySQL 为 null, PG 为 public/schema
                    String tableComment = tables.getString("REMARKS");

                    // 构建全限定表名 (Schema.Table)
                    String fullTableName = tableName;
                    if (tableSchema != null && !tableSchema.isBlank()) {
                        fullTableName = tableSchema + "." + tableName; // PG: public.users
                    } else if ("mysql".equalsIgnoreCase(ds.getDbType())) {
                        fullTableName = ds.getDbName() + "." + tableName; // MySQL: db_name.users
                        // 修正：后续 getColumns 等需要用正确参数
                        tableSchema = null; // MySQL schema 依然为 null
                    }

                    // 构建表的 Schema 描述文档
                    StringBuilder schemaText = new StringBuilder();
                    schemaText.append("表名: ").append(fullTableName).append("\n");
                    if (tableComment != null && !tableComment.isEmpty()) {
                        schemaText.append("表说明: ").append(tableComment).append("\n");
                    }
                    schemaText.append("字段列表:\n");

                    // 获取字段信息
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

                    // 获取主键信息
                    List<String> primaryKeys = new ArrayList<>();
                    try (ResultSet pks = metaData.getPrimaryKeys(ds.getDbName(), tableSchema, tableName)) {
                        while (pks.next()) {
                            primaryKeys.add(pks.getString("COLUMN_NAME"));
                        }
                    }
                    if (!primaryKeys.isEmpty()) {
                        schemaText.append("主键: ").append(String.join(", ", primaryKeys)).append("\n");
                    }

                    metaList.add(Map.of("tableName", fullTableName));

                    if (contentList.isEmpty()) {
                        log.info("提取到的第一个表 Schema 示例:\n{}", schemaText);
                    }
                    contentList.add(schemaText.toString());

                    log.debug("提取表 [{}] 的 Schema 完成", fullTableName);
                }
            }

            if (contentList.isEmpty()) {
                log.warn("数据源 [{}] 未提取到任何表结构，请检查数据库权限或连接配置。", ds.getName());
            } else {
                log.info("共提取到 {} 张表，准备存入向量库...", contentList.size());
            }

            // 先删除该数据源的旧 Schema 向量数据
            vectorStoreService.deleteByDataSourceAndType(dataSourceId, "schema");

            // 批量写入向量（调用 embedding-3）
            if (!contentList.isEmpty()) {
                vectorStoreService.addDocuments(contentList, dataSourceId, "schema", metaList);
                log.info("数据源 [{}] Schema 同步完成，共 {} 张表", ds.getName(), contentList.size());
            }

            // 更新同步时间
            ds.setSchemaSyncedAt(LocalDateTime.now());
            dataSourceRepository.save(ds);

        } catch (Exception e) {
            log.error("同步数据源 [{}] Schema 失败: {}", ds.getName(), e.getMessage(), e);
            throw new RuntimeException("Schema 同步失败: " + e.getMessage(), e);
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
