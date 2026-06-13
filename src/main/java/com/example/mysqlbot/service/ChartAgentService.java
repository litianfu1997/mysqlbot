package com.example.mysqlbot.service;

import com.example.mysqlbot.model.LlmConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 制图 Agent：工具驱动地探查查询结果数据形态，直出完整 ECharts 配置。
 *
 * <p>复用 {@link AgentService} 的工具循环骨架，工具来自 {@link ChartToolService}
 * （纯内存、操作当前结果集）。最终输出 {@code {insight, chartType, chartOption}}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChartAgentService {

    private final AgentService agentService;
    private final ChartToolService chartToolService;
    private final ObjectMapper objectMapper;

    @Value("${mysqlbot.chart.max-rounds:3}")
    private int chartMaxRounds;

    private String chartAgentPrompt;

    @PostConstruct
    public void init() {
        chartAgentPrompt = loadResource("prompts/chart-agent.st");
        log.info("ChartAgentService: prompt template cached ({} chars)", chartAgentPrompt.length());
    }

    public ChartResult generate(String question, String sql, List<Map<String, Object>> rows, LlmConfig llmConfig) {
        if (rows == null || rows.isEmpty()) {
            return ChartResult.builder().insight("查询结果为空，无可视化内容。").chartType("Table").build();
        }

        String columns = String.join(", ", rows.get(0).keySet());
        String systemContent = chartAgentPrompt
                .replace("{question}", question != null ? question : "")
                .replace("{sql}", sql != null ? sql : "")
                .replace("{columns}", columns)
                .replace("{rowCount}", String.valueOf(rows.size()));

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemContent));
        messages.add(Map.of("role", "user", "content", "请先探查数据形态，再产出最合适的图表配置与洞察。"));

        List<Map<String, Object>> tools = chartToolService.getToolDefinitions();
        AgentService.ToolExecutor executor = (name, args) -> chartToolService.execute(name, args, rows);

        log.debug("ChartAgent: starting loop, rows={}, columns={}", rows.size(), columns);
        String response = agentService.runAgentLoop(messages, tools, 0.3, llmConfig, executor, chartMaxRounds);
        log.debug("ChartAgent response: {}", response);

        return parseResponse(response);
    }

    private ChartResult parseResponse(String response) {
        try {
            String json = extractJson(response);
            if (json != null) {
                JsonNode root = objectMapper.readTree(json);
                String insight = root.has("insight") ? root.get("insight").asText() : null;
                String chartType = root.has("chartType") ? root.get("chartType").asText() : "Table";
                String chartOption = null;
                JsonNode optionNode = root.get("chartOption");
                if (optionNode != null && !optionNode.isNull() && optionNode.isObject()) {
                    chartOption = objectMapper.writeValueAsString(optionNode);
                }
                // 无有效 option 时回退表格，避免前端拿到空图
                if (chartOption == null && !"Table".equalsIgnoreCase(chartType)) {
                    chartType = "Table";
                }
                return ChartResult.builder()
                        .insight(insight)
                        .chartType(chartType)
                        .chartOption(chartOption)
                        .build();
            }
        } catch (Exception e) {
            log.warn("ChartAgent: failed to parse response: {}", e.getMessage());
        }
        return ChartResult.builder().insight(response).chartType("Table").build();
    }

    /** 去除 markdown 围栏并提取第一个完整 JSON 对象。 */
    private String extractJson(String response) {
        if (response == null) return null;
        String trimmed = response.replaceAll("```json", "").replaceAll("```", "").trim();
        int start = trimmed.indexOf('{');
        if (start < 0) return null;
        int depth = 0;
        boolean inString = false;
        char prev = 0;
        for (int i = start; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (inString) {
                if (c == '"' && prev != '\\') inString = false;
            } else {
                if (c == '"') inString = true;
                else if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) return trimmed.substring(start, i + 1);
                }
            }
            prev = c;
        }
        return null;
    }

    private static String loadResource(String path) {
        try (var is = new org.springframework.core.io.ClassPathResource(path).getInputStream()) {
            return StreamUtils.copyToString(is, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load resource: " + path, e);
        }
    }

    @Data
    @Builder
    public static class ChartResult {
        private String insight;
        private String chartType;
        /** 完整 ECharts option 的 JSON 字符串；chartType=Table 时为 null。 */
        private String chartOption;
    }
}
