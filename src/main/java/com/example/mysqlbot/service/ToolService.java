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
 *
 * Note: data_source_id is NOT included in the tool JSON schemas — it is injected
 * server-side by AgentService before each tool call. This prevents the LLM from
 * needing to know or guess the correct datasource ID.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolService {

    private final DataSourceRepository dataSourceRepository;
    private final TableRelationRepository tableRelationRepository;

    private static final int TOOL_QUERY_TIMEOUT_SECONDS = 10;

    /**
     * Returns tool definitions in OpenAI-compatible format.
     * data_source_id is intentionally omitted — it is injected server-side.
     */
    public List<Map<String, Object>> getToolDefinitions() {
        return List.of(
                Map.of(
                        "type", "function",
                        "function", Map.of(
                                "name", "list_tables",
                                "description", "列出数据源中的所有表名。当你需要确认哪些表可用时调用此工具。",
                                "parameters", Map.of(
                                        "type", "object",
                                        "properties", Map.of(),
                                        "required", List.of()
                                )
                        )
                ),
                Map.of(
                        "type", "function",
                        "function", Map.of(
                                "name", "get_table_schema",
                                "description", "获取指定表的列信息（列名、数据类型、注释）。在生成 SQL 前必须调用此工具确认涉及每张表的列结构。",
                                "parameters", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "table_name", Map.of(
                                                        "type", "string",
                                                        "description", "表名"
                                                )
                                        ),
                                        "required", List.of("table_name")
                                )
                        )
                ),
                Map.of(
                        "type", "function",
                        "function", Map.of(
                                "name", "get_table_relations",
                                "description", "获取表的关联关系（用于确定 JOIN 键）。多表查询时必须调用此工具，JOIN 键只能来自此工具的返回结果，不得臆造。",
                                "parameters", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "table_name", Map.of(
                                                        "type", "string",
                                                        "description", "表名（为空时返回该数据源所有关系）"
                                                )
                                        ),
                                        "required", List.of()
                                )
                        )
                ),
                Map.of(
                        "type", "function",
                        "function", Map.of(
                                "name", "search_tables_by_column",
                                "description", "按列名模式搜索包含该列的所有表。当你知道字段名但不确定在哪张表时调用此工具。",
                                "parameters", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "pattern", Map.of(
                                                        "type", "string",
                                                        "description", "列名关键词（模糊匹配）"
                                                )
                                        ),
                                        "required", List.of("pattern")
                                )
                        )
                ),
                Map.of(
                        "type", "function",
                        "function", Map.of(
                                "name", "get_sample_data",
                                "description", "获取指定表的样例数据（前 5 行）。当需要预览数据内容或确认字段值格式时调用此工具。",
                                "parameters", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "table_name", Map.of(
                                                        "type", "string",
                                                        "description", "表名"
                                                )
                                        ),
                                        "required", List.of("table_name")
                                )
                        )
                )
        );
    }

    /**
     * 获取数据源中所有表名列表（仅表名，供 prompt 注入起点提示）。
     */
    public List<String> listTableNames(Long dataSourceId) {
        DataSource ds = dataSourceRepository.findById(dataSourceId).orElse(null);
        if (ds == null) return List.of();
        List<String> tables = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(ds.buildJdbcUrl(), ds.getUsername(), ds.getPassword())) {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet rs = meta.getTables(ds.getDbName(), null, "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    tables.add(rs.getString("TABLE_NAME"));
                }
            }
        } catch (Exception e) {
            log.warn("listTableNames failed for dataSourceId={}: {}", dataSourceId, e.getMessage());
        }
        return tables;
    }

    /**
     * Execute a tool call and return the result as a string.
     * data_source_id must already be present in arguments (injected by AgentService).
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

        DataSource ds = dataSourceRepository.findById(dsId.longValue()).orElse(null);
        if (ds == null) return "错误：数据源不存在";

        List<String> tables = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(ds.buildJdbcUrl(), ds.getUsername(), ds.getPassword())) {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet rs = meta.getTables(ds.getDbName(), null, "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    tables.add(rs.getString("TABLE_NAME"));
                }
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

        DataSource ds = dataSourceRepository.findById(dsId.longValue()).orElse(null);
        if (ds == null) return "错误：数据源不存在";

        StringBuilder result = new StringBuilder();
        try (Connection conn = DriverManager.getConnection(ds.buildJdbcUrl(), ds.getUsername(), ds.getPassword())) {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet cols = meta.getColumns(ds.getDbName(), null, tableName, "%")) {
                result.append("表 ").append(tableName).append(" 的列信息：\n");
                while (cols.next()) {
                    result.append("  - ").append(cols.getString("COLUMN_NAME"))
                          .append(" (").append(cols.getString("TYPE_NAME")).append(")");
                    String comment = cols.getString("REMARKS");
                    if (comment != null && !comment.isBlank()) result.append(" // ").append(comment);
                    result.append("\n");
                }
            }
        } catch (Exception e) {
            return "错误：" + e.getMessage();
        }
        if (result.length() == ("表 " + tableName + " 的列信息：\n").length()) {
            return "未找到表 " + tableName + "，请用 list_tables 确认表名是否正确。";
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
            return "未找到关联关系。如需建立关联，请在设置页面手动添加表关系，或先执行数据源同步以推断关系。";
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

        DataSource ds = dataSourceRepository.findById(dsId.longValue()).orElse(null);
        if (ds == null) return "错误：数据源不存在";

        List<String> matchedTables = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(ds.buildJdbcUrl(), ds.getUsername(), ds.getPassword())) {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet rs = meta.getTables(ds.getDbName(), null, "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    String tName = rs.getString("TABLE_NAME");
                    try (ResultSet cols = meta.getColumns(ds.getDbName(), null, tName, null)) {
                        while (cols.next()) {
                            String colName = cols.getString("COLUMN_NAME");
                            if (colName.toLowerCase().contains(pattern.toLowerCase())) {
                                matchedTables.add(tName);
                                break;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            return "错误：" + e.getMessage();
        }

        if (matchedTables.isEmpty()) {
            return "未找到包含列关键词 '" + pattern + "' 的表。";
        }
        return "包含列 '" + pattern + "' 的表：" + String.join(", ", matchedTables);
    }

    private String getSampleData(Map<String, Object> arguments) {
        Number dsId = (Number) arguments.get("data_source_id");
        String tableName = (String) arguments.get("table_name");
        if (dsId == null || tableName == null) return "错误：缺少 data_source_id 或 table_name 参数";

        DataSource ds = dataSourceRepository.findById(dsId.longValue()).orElse(null);
        if (ds == null) return "错误：数据源不存在";

        StringBuilder result = new StringBuilder();
        try (Connection conn = DriverManager.getConnection(ds.buildJdbcUrl(), ds.getUsername(), ds.getPassword())) {
            var stmt = conn.createStatement();
            stmt.setQueryTimeout(TOOL_QUERY_TIMEOUT_SECONDS);
            stmt.setMaxRows(5);
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT * FROM " + ds.getDialect().quoteIdentifier(tableName) + " LIMIT 5")) {
                int colCount = rs.getMetaData().getColumnCount();
                for (int i = 1; i <= colCount; i++) {
                    if (i > 1) result.append("\t");
                    result.append(rs.getMetaData().getColumnName(i));
                }
                result.append("\n");

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
            }
        } catch (Exception e) {
            return "错误：" + e.getMessage();
        }
        return result.toString();
    }
}
