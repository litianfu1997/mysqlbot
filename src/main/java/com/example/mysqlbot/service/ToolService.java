package com.example.mysqlbot.service;

import com.example.mysqlbot.model.DataSource;
import com.example.mysqlbot.repository.TableRelationRepository;
import com.example.mysqlbot.repository.DataSourceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Tool execution service for LLM Function Calling.
 * Provides database introspection tools that LLM can call to gather information.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolService {

    private final DataSourceRepository dataSourceRepository;
    private final TableRelationRepository tableRelationRepository;
    private final VectorStoreService vectorStoreService;

    /**
     * Returns tool definitions in OpenAI-compatible format.
     */
    public List<Map<String, Object>> getToolDefinitions() {
        return List.of(
                Map.of(
                        "type", "function",
                        "function", Map.of(
                                "name", "list_tables",
                                "description", "列出数据源中的所有表名。当你不确定有哪些表可用，或需要验证表名时调用此工具。",
                                "parameters", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "data_source_id", Map.of(
                                                        "type", "integer",
                                                        "description", "数据源ID"
                                                )
                                        ),
                                        "required", List.of("data_source_id")
                                )
                        )
                ),
                Map.of(
                        "type", "function",
                        "function", Map.of(
                                "name", "get_table_schema",
                                "description", "获取指定表的列信息（列名、数据类型、注释）。当你需要了解某张表的详细结构（有哪些列、类型是什么）时调用此工具。",
                                "parameters", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "data_source_id", Map.of(
                                                        "type", "integer",
                                                        "description", "数据源ID"
                                                ),
                                                "table_name", Map.of(
                                                        "type", "string",
                                                        "description", "表名"
                                                )
                                        ),
                                        "required", List.of("data_source_id", "table_name")
                                )
                        )
                ),
                Map.of(
                        "type", "function",
                        "function", Map.of(
                                "name", "get_table_relations",
                                "description", "获取表的关联关系（用于多表 JOIN）。当你需要确定两张表之间如何关联、有哪些外键或命名约定推断的关系时调用此工具。",
                                "parameters", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "data_source_id", Map.of(
                                                        "type", "integer",
                                                        "description", "数据源ID"
                                                ),
                                                "table_name", Map.of(
                                                        "type", "string",
                                                        "description", "表名（为空时返回该数据源所有关系）"
                                                )
                                        ),
                                        "required", List.of("data_source_id")
                                )
                        )
                ),
                Map.of(
                        "type", "function",
                        "function", Map.of(
                                "name", "search_tables_by_column",
                                "description", "按列名模式搜索包含该列的所有表。当你知道某个字段名但不确定它在哪张表时调用此工具。",
                                "parameters", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "data_source_id", Map.of(
                                                        "type", "integer",
                                                        "description", "数据源ID"
                                                ),
                                                "pattern", Map.of(
                                                        "type", "string",
                                                        "description", "列名模式（可含 % 通配符）"
                                                )
                                        ),
                                        "required", List.of("data_source_id", "pattern")
                                )
                        )
                ),
                Map.of(
                        "type", "function",
                        "function", Map.of(
                                "name", "get_sample_data",
                                "description", "获取指定表的样例数据（前 5 行）。当你需要预览表中的数据内容或验证数据分布时调用此工具。",
                                "parameters", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "data_source_id", Map.of(
                                                        "type", "integer",
                                                        "description", "数据源ID"
                                                ),
                                                "table_name", Map.of(
                                                        "type", "string",
                                                        "description", "表名"
                                                )
                                        ),
                                        "required", List.of("data_source_id", "table_name")
                                )
                        )
                )
        );
    }

    /**
     * Execute a tool call and return the result as a string.
     */
    public String executeTool(String toolName, Map<String, Object> arguments) {
        try {
            return switchTool(toolName, arguments);
        } catch (Exception e) {
            log.error("Tool execution failed: tool={}, error={}", toolName, e.getMessage(), e);
            return "工具执行失败：" + e.getMessage();
        }
    }

    private String switchTool(String toolName, Map<String, Object> arguments) {
        return switch (toolName) {
            case "list_tables" -> listTables(arguments);
            case "get_table_schema" -> getTableSchema(arguments);
            case "get_table_relations" -> getTableRelations(arguments);
            case "search_tables_by_column" -> searchTablesByColumn(arguments);
            case "get_sample_data" -> getSampleData(arguments);
            default -> "未知工具：" + toolName;
        };
    }

    // ===== Tool Implementations =====

    private String listTables(Map<String, Object> arguments) {
        Number dsId = (Number) arguments.get("data_source_id");
        if (dsId == null) return "错误：缺少 data_source_id 参数";

        DataSource ds = dataSourceRepository.findById(dsId.longValue())
                .orElse(null);
        if (ds == null) return "错误：数据源不存在";

        List<String> tables = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(ds.buildJdbcUrl(), ds.getUsername(), ds.getPassword())) {
            DatabaseMetaData meta = conn.getMetaData();
            ResultSet rs = meta.getTables(ds.getDbName(), null, "%", new String[]{"TABLE"});
            while (rs.next()) {
                tables.add(rs.getString("TABLE_NAME"));
            }
        } catch (Exception e) {
            return "错误：" + e.getMessage();
        }
        return "表列表：" + String.join(", ", tables);
    }

    private String getTableSchema(Map<String, Object> arguments) {
        Number dsId = (Number) arguments.get("data_source_id");
        String tableName = (String) arguments.get("table_name");
        if (dsId == null || tableName == null) return "错误：缺少 data_source_id 或 table_name 参数";

        DataSource ds = dataSourceRepository.findById(dsId.longValue())
                .orElse(null);
        if (ds == null) return "错误：数据源不存在";

        StringBuilder result = new StringBuilder();
        try (Connection conn = DriverManager.getConnection(ds.buildJdbcUrl(), ds.getUsername(), ds.getPassword())) {
            DatabaseMetaData meta = conn.getMetaData();
            ResultSet cols = meta.getColumns(ds.getDbName(), null, tableName, "%");
            result.append("表 ").append(tableName).append(" 的列信息：\n");
            while (cols.next()) {
                result.append("  - ").append(cols.getString("COLUMN_NAME"))
                      .append(" (").append(cols.getString("TYPE_NAME")).append(")");
                String comment = cols.getString("REMARKS");
                if (comment != null && !comment.isBlank()) result.append(" // ").append(comment);
                result.append("\n");
            }
        } catch (Exception e) {
            return "错误：" + e.getMessage();
        }
        return result.toString();
    }

    private String getTableRelations(Map<String, Object> arguments) {
        Number dsId = (Number) arguments.get("data_source_id");
        String tableName = (String) arguments.get("table_name");
        if (dsId == null) return "错误：缺少 data_source_id 参数";

        List<com.example.mysqlbot.model.TableRelation> relations;
        if (tableName == null || tableName.isBlank()) {
            relations = tableRelationRepository.findByDataSourceIdAndIsActive(dsId.longValue(), 1);
        } else {
            relations = tableRelationRepository.safelyFindRelationsInvolvingTables(
                    dsId.longValue(), List.of(tableName));
        }

        if (relations.isEmpty()) {
            return "未找到关联关系。";
        }

        StringBuilder result = new StringBuilder("表间关系：\n");
        for (var r : relations) {
            result.append("  ").append(r.getFromTable()).append(".").append(r.getFromColumn())
                  .append(" → ").append(r.getToTable()).append(".").append(r.getToColumn())
                  .append(" [来源:").append(r.getSource()).append(", 置信度:").append(r.getConfidence()).append("]\n");
        }
        return result.toString();
    }

    private String searchTablesByColumn(Map<String, Object> arguments) {
        Number dsId = (Number) arguments.get("data_source_id");
        String pattern = (String) arguments.get("pattern");
        if (dsId == null || pattern == null) return "错误：缺少 data_source_id 或 pattern 参数";

        DataSource ds = dataSourceRepository.findById(dsId.longValue())
                .orElse(null);
        if (ds == null) return "错误：数据源不存在";

        List<String> matchedTables = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(ds.buildJdbcUrl(), ds.getUsername(), ds.getPassword())) {
            DatabaseMetaData meta = conn.getMetaData();
            ResultSet rs = meta.getTables(ds.getDbName(), null, "%", new String[]{"TABLE"});
            while (rs.next()) {
                String tableName = rs.getString("TABLE_NAME");
                // 检查该表是否有匹配的列
                ResultSet cols = meta.getColumns(ds.getDbName(), null, tableName, null);
                while (cols.next()) {
                    String colName = cols.getString("COLUMN_NAME");
                    if (colName.toLowerCase().contains(pattern.toLowerCase()) || colName.equals(pattern)) {
                        matchedTables.add(tableName);
                        break;
                    }
                }
            }
        } catch (Exception e) {
            return "错误：" + e.getMessage();
        }

        if (matchedTables.isEmpty()) {
            return "未找到匹配的表。";
        }
        return "包含列 '" + pattern + "' 的表：" + String.join(", ", matchedTables);
    }

    private String getSampleData(Map<String, Object> arguments) {
        Number dsId = (Number) arguments.get("data_source_id");
        String tableName = (String) arguments.get("table_name");
        if (dsId == null || tableName == null) return "错误：缺少 data_source_id 或 table_name 参数";

        DataSource ds = dataSourceRepository.findById(dsId.longValue())
                .orElse(null);
        if (ds == null) return "错误：数据源不存在";

        // 使用 SqlExecuteService 的逻辑执行查询（复用连接池）
        // 简化实现：直接查询
        StringBuilder result = new StringBuilder();
        try (Connection conn = DriverManager.getConnection(ds.buildJdbcUrl(), ds.getUsername(), ds.getPassword())) {
            var stmt = conn.createStatement();
            stmt.setMaxRows(5);
            ResultSet rs = stmt.executeQuery("SELECT * FROM " + ds.getDialect().quoteIdentifier(tableName) + " LIMIT 5");
            int colCount = rs.getMetaData().getColumnCount();

            // 列头
            for (int i = 1; i <= colCount; i++) {
                if (i > 1) result.append("\t");
                result.append(rs.getMetaData().getColumnName(i));
            }
            result.append("\n");

            // 数据行
            int rowCount = 0;
            while (rs.next() && rowCount < 5) {
                for (int i = 1; i <= colCount; i++) {
                    if (i > 1) result.append("\t");
                    Object val = rs.getObject(i);
                    String valStr = (val == null) ? "NULL" : val.toString();
                    if (valStr.length() > 50) valStr = valStr.substring(0, 47) + "...";
                    result.append(valStr);
                }
                result.append("\n");
                rowCount++;
            }
            if (rowCount == 0) result.append("(表为空)");
        } catch (Exception e) {
            return "错误：" + e.getMessage();
        }
        return result.toString();
    }
}
