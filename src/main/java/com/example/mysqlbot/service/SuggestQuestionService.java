package com.example.mysqlbot.service;

import com.example.mysqlbot.model.LlmConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 追问建议 Agent：工具驱动地探查结果数据与数据库关联关系，
 * 产出紧扣真实字段、可下钻的高相关追问。
 *
 * <p>复用 {@link AgentService} 工具循环；工具按名路由：结果探查走 {@link ChartToolService}
 * （内存 rows），schema 探查走 {@link ToolService}（注入 data_source_id）。
 * 旧的无数据上下文调用（如各 IM Bot）传 rows/dataSourceId 为空时自动降级为单次推荐。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SuggestQuestionService {

    private final AgentService agentService;
    private final ToolService toolService;
    private final ChartToolService chartToolService;
    private final ObjectMapper objectMapper;

    @Value("${mysqlbot.suggest.max-rounds:3}")
    private int suggestMaxRounds;

    private String suggestAgentPrompt;

    @PostConstruct
    public void init() {
        suggestAgentPrompt = loadResource("prompts/suggest-agent.st");
        log.info("SuggestQuestionService: prompt template cached ({} chars)", suggestAgentPrompt.length());
    }

    // ===== 兼容旧调用点（无数据上下文，降级为单次推荐）=====

    public List<String> suggest(String question, String sql) {
        return suggest(question, sql, null);
    }

    public List<String> suggest(String question, String sql, LlmConfig llmConfig) {
        return suggest(question, sql, null, null, null, llmConfig);
    }

    // ===== 主入口：带结果数据 + 洞察 + 数据源（工具驱动）=====

    public List<String> suggest(String question, String sql, List<Map<String, Object>> rows,
                                String insight, Long dataSourceId, LlmConfig llmConfig) {
        boolean hasRows = rows != null && !rows.isEmpty();
        String columns = hasRows ? String.join(", ", rows.get(0).keySet()) : "(无)";
        int rowCount = rows != null ? rows.size() : 0;

        String systemContent = suggestAgentPrompt
                .replace("{question}", question != null ? question : "")
                .replace("{sql}", sql != null ? sql : "(no SQL)")
                .replace("{columns}", columns)
                .replace("{rowCount}", String.valueOf(rowCount))
                .replace("{insight}", insight != null && !insight.isBlank() ? insight : "(无)");

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemContent));
        messages.add(Map.of("role", "user", "content", "请推荐 3 个高相关、可深入的后续追问。"));

        // 动态组装工具集：有结果集 → 结果探查工具；有数据源 → schema 探查工具
        List<Map<String, Object>> tools = new ArrayList<>();
        if (hasRows) tools.addAll(chartToolService.getToolDefinitions());
        if (dataSourceId != null) tools.addAll(toolService.getToolDefinitions());

        final List<Map<String, Object>> rowsRef = rows;
        AgentService.ToolExecutor executor = (name, args) -> {
            if (hasRows && chartToolService.supports(name)) return chartToolService.execute(name, args, rowsRef);
            if (dataSourceId != null) {
                args.put("data_source_id", dataSourceId);
                return toolService.executeTool(name, args);
            }
            return "工具不可用";
        };

        try {
            String response = agentService.runAgentLoop(
                    messages, tools.isEmpty() ? null : tools, 0.5, llmConfig, executor, suggestMaxRounds);
            log.debug("Suggest agent response:\n{}", response);
            return parseLlmResponse(response);
        } catch (Exception e) {
            log.warn("Suggest agent failed: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private List<String> parseLlmResponse(String response) {
        try {
            String cleanerJson = response.replaceAll("```json", "").replaceAll("```", "").trim();
            // 容错：截取首个 '[' 到末个 ']'
            int s = cleanerJson.indexOf('['), e = cleanerJson.lastIndexOf(']');
            if (s >= 0 && e > s) cleanerJson = cleanerJson.substring(s, e + 1);
            return objectMapper.readValue(cleanerJson, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse LLM suggested questions: {}", response);
            List<String> list = new ArrayList<>();
            for (String line : response.split("\n")) {
                line = line.trim();
                if (line.startsWith("-") || line.matches("^\\d+\\..*")) {
                    line = line.replaceAll("^[-*\\d+..]\\s*", "");
                    if (!line.isEmpty()) list.add(line);
                }
            }
            return list;
        }
    }

    private static String loadResource(String path) {
        try (var is = new org.springframework.core.io.ClassPathResource(path).getInputStream()) {
            return StreamUtils.copyToString(is, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load resource: " + path, e);
        }
    }
}
