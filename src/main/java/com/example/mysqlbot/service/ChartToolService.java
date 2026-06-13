package com.example.mysqlbot.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 制图 / 追问 Agent 共用的结果集探查工具。
 *
 * <p>纯内存、无状态：所有工具以当前查询结果 {@code rows} 作为入参计算，
 * 不持有任何跨调用状态，避免多个 agent 并发共用时的状态耦合。
 * 工具定义采用 OpenAI tools 格式（仿 {@link ToolService}），不需要 data_source_id。
 */
@Slf4j
@Service
public class ChartToolService {

    private static final Set<String> SUPPORTED = Set.of(
            "profile_columns", "get_distinct_values", "get_numeric_distribution");

    /** 该工具名是否由本服务处理（供追问 agent 的 executor 路由判断）。 */
    public boolean supports(String toolName) {
        return SUPPORTED.contains(toolName);
    }

    /**
     * 工具定义（OpenAI tools 格式）。这些工具操作"当前查询结果集"，无需 data_source_id。
     */
    public List<Map<String, Object>> getToolDefinitions() {
        return List.of(
                Map.of(
                        "type", "function",
                        "function", Map.of(
                                "name", "profile_columns",
                                "description", "对当前查询结果集做整体画像：每列的推断类型（数值/日期/类别/文本）、非空数、去重基数，数值列的 min/max/avg、日期列的范围。决定图表前应先调用此工具了解数据形态。",
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
                                "name", "get_distinct_values",
                                "description", "获取某一列的去重值及其出现次数（按次数降序）。用于判断该列是否适合作为分类轴或饼图维度。",
                                "parameters", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "column", Map.of("type", "string", "description", "列名"),
                                                "limit", Map.of("type", "integer", "description", "返回的去重值数量上限，默认 20")
                                        ),
                                        "required", List.of("column")
                                )
                        )
                ),
                Map.of(
                        "type", "function",
                        "function", Map.of(
                                "name", "get_numeric_distribution",
                                "description", "对某个数值列做分箱分布统计。用于判断是否适合直方图或散点图。",
                                "parameters", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "column", Map.of("type", "string", "description", "数值列名"),
                                                "bins", Map.of("type", "integer", "description", "分箱数量，默认 10")
                                        ),
                                        "required", List.of("column")
                                )
                        )
                )
        );
    }

    /**
     * 执行工具：以 {@code rows} 作为数据上下文。
     */
    public String execute(String toolName, Map<String, Object> args, List<Map<String, Object>> rows) {
        try {
            if (rows == null || rows.isEmpty()) return "结果集为空，无数据可供分析。";
            return switch (toolName) {
                case "profile_columns" -> profileColumns(rows);
                case "get_distinct_values" -> getDistinctValues(args, rows);
                case "get_numeric_distribution" -> getNumericDistribution(args, rows);
                default -> "未知工具：" + toolName;
            };
        } catch (Exception e) {
            log.error("Chart tool execution failed: tool={}, error={}", toolName, e.getMessage(), e);
            return "工具执行失败：" + e.getMessage();
        }
    }

    // ===== Tool implementations =====

    private String profileColumns(List<Map<String, Object>> rows) {
        Set<String> columns = collectColumns(rows);
        StringBuilder sb = new StringBuilder();
        sb.append("结果集共 ").append(rows.size()).append(" 行，").append(columns.size()).append(" 列。列画像：\n");

        for (String col : columns) {
            List<Object> nonNull = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Object v = row.get(col);
                if (v != null && !(v instanceof String s && s.isBlank())) nonNull.add(v);
            }
            long distinct = nonNull.stream().map(String::valueOf).distinct().count();
            String type = inferType(nonNull);

            sb.append("  - ").append(col).append(" (").append(type)
              .append(", 非空 ").append(nonNull.size())
              .append(", 去重 ").append(distinct);

            if ("数值".equals(type)) {
                double min = Double.MAX_VALUE, max = -Double.MAX_VALUE, sum = 0;
                int cnt = 0;
                for (Object v : nonNull) {
                    Double d = toDouble(v);
                    if (d == null) continue;
                    min = Math.min(min, d); max = Math.max(max, d); sum += d; cnt++;
                }
                if (cnt > 0) {
                    sb.append(String.format(", min=%s, max=%s, avg=%s",
                            fmt(min), fmt(max), fmt(sum / cnt)));
                }
            } else if ("日期".equals(type)) {
                String minS = null, maxS = null;
                for (Object v : nonNull) {
                    String s = String.valueOf(v);
                    if (minS == null || s.compareTo(minS) < 0) minS = s;
                    if (maxS == null || s.compareTo(maxS) > 0) maxS = s;
                }
                sb.append(", 范围=[").append(minS).append(" ~ ").append(maxS).append("]");
            } else if ("类别".equals(type)) {
                // 列出少量示例类别值
                List<String> samples = nonNull.stream().map(String::valueOf).distinct().limit(5).toList();
                sb.append(", 示例=").append(samples);
            }
            sb.append(")\n");
        }
        sb.append("提示：可对类别列调用 get_distinct_values 查看分布，对数值列调用 get_numeric_distribution。");
        return sb.toString();
    }

    private String getDistinctValues(Map<String, Object> args, List<Map<String, Object>> rows) {
        String column = (String) args.get("column");
        if (column == null || column.isBlank()) return "错误：缺少 column 参数";
        int limit = toInt(args.get("limit"), 20);

        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Object v = row.get(column);
            String key = v == null ? "(NULL)" : String.valueOf(v);
            counts.merge(key, 1, Integer::sum);
        }
        if (counts.isEmpty()) return "列 " + column + " 无数据，请确认列名是否正确。";

        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort((a, b) -> b.getValue() - a.getValue());

        StringBuilder sb = new StringBuilder();
        sb.append("列 ").append(column).append(" 共 ").append(counts.size()).append(" 个去重值（按出现次数降序，最多 ").append(limit).append(" 个）：\n");
        int shown = 0;
        for (Map.Entry<String, Integer> e : sorted) {
            if (shown++ >= limit) break;
            sb.append("  - ").append(e.getKey()).append(": ").append(e.getValue()).append(" 次\n");
        }
        if (counts.size() > limit) sb.append("  …（其余 ").append(counts.size() - limit).append(" 个未列出）\n");
        return sb.toString();
    }

    private String getNumericDistribution(Map<String, Object> args, List<Map<String, Object>> rows) {
        String column = (String) args.get("column");
        if (column == null || column.isBlank()) return "错误：缺少 column 参数";
        int bins = toInt(args.get("bins"), 10);
        if (bins < 1) bins = 10;

        List<Double> nums = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Double d = toDouble(row.get(column));
            if (d != null) nums.add(d);
        }
        if (nums.isEmpty()) return "列 " + column + " 不是数值列或无有效数值。";

        double min = nums.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double max = nums.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        if (min == max) {
            return "列 " + column + " 所有值均为 " + fmt(min) + "（共 " + nums.size() + " 个）。";
        }
        double width = (max - min) / bins;
        int[] hist = new int[bins];
        for (double d : nums) {
            int idx = (int) ((d - min) / width);
            if (idx >= bins) idx = bins - 1;
            if (idx < 0) idx = 0;
            hist[idx]++;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("列 ").append(column).append(" 数值分布（min=").append(fmt(min)).append(", max=").append(fmt(max)).append("）：\n");
        for (int i = 0; i < bins; i++) {
            double lo = min + i * width, hi = (i == bins - 1) ? max : lo + width;
            sb.append(String.format("  [%s ~ %s): %d\n", fmt(lo), fmt(hi), hist[i]));
        }
        return sb.toString();
    }

    // ===== Helpers =====

    private Set<String> collectColumns(List<Map<String, Object>> rows) {
        Set<String> columns = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) columns.addAll(row.keySet());
        return columns;
    }

    /** 推断列类型：数值 / 日期 / 类别 / 文本 / 空。 */
    private String inferType(List<Object> nonNull) {
        if (nonNull.isEmpty()) return "空";
        boolean allNumeric = nonNull.stream().allMatch(v -> toDouble(v) != null);
        if (allNumeric) return "数值";
        boolean allDate = nonNull.stream().allMatch(this::isDateLike);
        if (allDate) return "日期";
        long distinct = nonNull.stream().map(String::valueOf).distinct().count();
        // 去重比例低且基数有限 → 类别
        if (distinct <= 50 && distinct <= nonNull.size() * 0.5) return "类别";
        return "文本";
    }

    private Double toDouble(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) {
            try { return Double.parseDouble(s.trim()); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    private boolean isDateLike(Object v) {
        if (v == null) return false;
        if (v instanceof java.util.Date || v instanceof java.time.temporal.Temporal) return true;
        if (v instanceof String s) {
            // yyyy-MM-dd / yyyy/MM/dd 可选时间部分；或 yyyy-MM / yyyy
            return s.matches("\\d{4}([-/]\\d{1,2}([-/]\\d{1,2}([ T]\\d{1,2}:\\d{2}(:\\d{2})?)?)?)?");
        }
        return false;
    }

    private int toInt(Object v, int dflt) {
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return dflt; }
        }
        return dflt;
    }

    private String fmt(double d) {
        if (d == Math.floor(d) && !Double.isInfinite(d)) return String.valueOf((long) d);
        return String.format("%.2f", d);
    }
}
