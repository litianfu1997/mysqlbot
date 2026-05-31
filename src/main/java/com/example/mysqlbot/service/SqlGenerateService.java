package com.example.mysqlbot.service;

import com.example.mysqlbot.config.AppConfig;
import com.example.mysqlbot.model.ChatMessage;
import com.example.mysqlbot.model.DataSource;
import com.example.mysqlbot.model.SqlExample;
import com.example.mysqlbot.model.DatabaseDialect;
import com.example.mysqlbot.model.LlmConfig;
import com.example.mysqlbot.model.TermGlossary;
import com.example.mysqlbot.repository.DataSourceRepository;
import com.example.mysqlbot.repository.SqlExampleRepository;
import com.example.mysqlbot.repository.TermGlossaryRepository;
import com.example.mysqlbot.util.OpenAiLlmUtil;
import jakarta.annotation.PostConstruct;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * SQL generation service — pure LLM tool-driven mode.
 * The LLM receives a table-name list as a starting hint and uses tools
 * (get_table_schema, get_table_relations, etc.) to explore the schema before
 * generating SQL. No vector retrieval (RAG) is performed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SqlGenerateService {

    private final AppConfig appConfig;
    private final TermGlossaryRepository termGlossaryRepository;
    private final SqlExampleRepository sqlExampleRepository;
    private final DataSourceRepository dataSourceRepository;
    private final LlmService llmService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final ToolService toolService;
    private final AgentService agentService;

    @Value("${mysqlbot.sql.max-retry:3}")
    private int maxRetry;

    private String sqlGeneratePrompt;

    private static final Pattern SQL_PATTERN = Pattern.compile(
            "```sql\\s*([\\s\\S]+?)\\s*```", Pattern.CASE_INSENSITIVE);

    @PostConstruct
    public void init() {
        sqlGeneratePrompt = loadResource("prompts/sql-generate.st");
        log.info("SqlGenerateService: prompt template cached ({} chars)", sqlGeneratePrompt.length());
    }

    // ---------------------------------------------------------------------------
    // Public API — conversation-list based (multi-turn)
    // ---------------------------------------------------------------------------

    public SqlGenerateResult generate(String question, Long dataSourceId,
                                      List<ChatMessage> conversation, LlmConfig llmConfig) {
        List<Map<String, Object>> messages = buildMessages(question, dataSourceId, conversation);
        List<Map<String, Object>> tools = toolService.getToolDefinitions();
        double temperature = resolveTemperature(llmConfig);

        log.debug("generate: starting agent loop, dataSourceId={}", dataSourceId);
        String llmResponse = agentService.runAgentLoop(messages, tools, dataSourceId, temperature, llmConfig);
        log.debug("generate: LLM response length={}", llmResponse.length());
        return parseLlmResponse(llmResponse, dataSourceId);
    }

    public SqlGenerateResult generateStream(String question, Long dataSourceId,
                                            List<ChatMessage> conversation,
                                            LlmConfig llmConfig, boolean thinking,
                                            OpenAiLlmUtil.StreamCallback tokenCallback) {
        List<Map<String, Object>> messages = buildMessages(question, dataSourceId, conversation);
        List<Map<String, Object>> tools = toolService.getToolDefinitions();
        double temperature = resolveTemperature(llmConfig);

        log.debug("generateStream: starting agent loop then stream, dataSourceId={}", dataSourceId);
        String llmResponse = agentService.runAgentLoopThenStream(
                messages, tools, dataSourceId, temperature, llmConfig, thinking, tokenCallback);
        log.debug("generateStream: response length={}", llmResponse.length());
        return parseLlmResponse(llmResponse, dataSourceId);
    }

    // ---------------------------------------------------------------------------
    // Backward-compatible overloads (String chatHistory)
    // ---------------------------------------------------------------------------

    public SqlGenerateResult generate(String question, Long dataSourceId, String chatHistory) {
        return generate(question, dataSourceId, chatHistory, null);
    }

    public SqlGenerateResult generate(String question, Long dataSourceId,
                                      String chatHistory, LlmConfig llmConfig) {
        List<ChatMessage> conversation = legacyHistoryToConversation(chatHistory, question);
        return generate(question, dataSourceId, conversation, llmConfig);
    }

    // ---------------------------------------------------------------------------
    // Core: build structured messages array
    // ---------------------------------------------------------------------------

    private List<Map<String, Object>> buildMessages(String question, Long dataSourceId,
                                                    List<ChatMessage> conversation) {
        DatabaseDialect dialect = resolveDialect(dataSourceId);

        // 表名清单作起点提示（仅表名，不含列——LLM 按需调工具深入）
        List<String> tableNames = toolService.listTableNames(dataSourceId);
        String tableList = tableNames.isEmpty()
                ? "（无法获取表列表，请调用 list_tables 工具）"
                : String.join(", ", tableNames);

        String termGlossary = buildTermGlossaryContext(dataSourceId);
        String sqlExamples = loadSqlExamples(dataSourceId);

        String systemContent = sqlGeneratePrompt
                .replace("{dbEngine}", dialect.getDisplayName())
                .replace("{quoteRules}", dialect.getQuotingRules())
                .replace("{tableList}", tableList)
                .replace("{termGlossary}", termGlossary)
                .replace("{sqlExamples}", sqlExamples);

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemContent));

        if (conversation != null) {
            for (ChatMessage msg : conversation) {
                if ("user".equals(msg.getRole())) {
                    Map<String, Object> m = new HashMap<>();
                    m.put("role", "user");
                    m.put("content", msg.getContent() != null ? msg.getContent() : "");
                    messages.add(m);
                } else if ("assistant".equals(msg.getRole())) {
                    Map<String, Object> m = new HashMap<>();
                    m.put("role", "assistant");
                    m.put("content", reconstructAssistantJson(msg));
                    messages.add(m);
                }
            }
        }

        log.debug("buildMessages: system + {} history turns, tableList={} tables",
                messages.size() - 1, tableNames.size());
        return messages;
    }

    private String reconstructAssistantJson(ChatMessage msg) {
        try {
            if (msg.getSqlQuery() != null && !msg.getSqlQuery().isBlank()) {
                Map<String, Object> json = new HashMap<>();
                json.put("success", true);
                json.put("sql", msg.getSqlQuery());
                json.put("brief", msg.getContent() != null ? msg.getContent() : "");
                return objectMapper.writeValueAsString(json);
            } else {
                Map<String, Object> json = new HashMap<>();
                json.put("success", false);
                json.put("message", msg.getContent() != null ? msg.getContent() : "");
                return objectMapper.writeValueAsString(json);
            }
        } catch (Exception e) {
            log.warn("reconstructAssistantJson failed: {}", e.getMessage());
            return msg.getContent() != null ? msg.getContent() : "";
        }
    }

    // ---------------------------------------------------------------------------
    // Response parsing
    // ---------------------------------------------------------------------------

    private SqlGenerateResult parseLlmResponse(String llmResponse, Long dataSourceId) {
        String sql = null;
        String explanation = llmResponse;
        List<String> tables = null;

        try {
            String jsonContent = extractJson(llmResponse);
            if (jsonContent != null) {
                com.fasterxml.jackson.databind.JsonNode rootNode = objectMapper.readTree(jsonContent);
                if (rootNode.has("success") && rootNode.get("success").asBoolean()) {
                    if (rootNode.has("sql")) sql = rootNode.get("sql").asText();
                    if (rootNode.has("brief")) explanation = rootNode.get("brief").asText();
                    if (rootNode.has("tables")) {
                        com.fasterxml.jackson.databind.JsonNode tablesNode = rootNode.get("tables");
                        if (tablesNode.isArray()) {
                            tables = new ArrayList<>();
                            for (com.fasterxml.jackson.databind.JsonNode t : tablesNode) tables.add(t.asText());
                        }
                    }
                } else if (rootNode.has("message")) {
                    explanation = rootNode.get("message").asText();
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse LLM response as JSON, trying regex: {}", e.getMessage());
        }

        if (sql == null) sql = extractSqlOld(llmResponse);

        return SqlGenerateResult.builder()
                .sql(sql)
                .explanation(explanation)
                .tables(tables)
                .success(sql != null)
                .build();
    }

    private String extractJson(String response) {
        if (response == null) return null;
        String trimmed = response.trim();

        java.util.regex.Pattern jsonBlockPattern = java.util.regex.Pattern.compile(
                "```(?:json)?\\s*\\n?(\\{[\\s\\S]*?\\})\\s*```");
        java.util.regex.Matcher blockMatcher = jsonBlockPattern.matcher(trimmed);
        String lastBlock = null;
        while (blockMatcher.find()) lastBlock = blockMatcher.group(1).trim();
        if (lastBlock != null) return lastBlock;

        for (int i = 0; i < trimmed.length(); i++) {
            if (trimmed.charAt(i) == '{') {
                int depth = 1, j = i + 1;
                while (j < trimmed.length() && depth > 0) {
                    char c = trimmed.charAt(j);
                    if (c == '{') depth++;
                    else if (c == '}') depth--;
                    j++;
                }
                if (depth == 0) {
                    String candidate = trimmed.substring(i, j);
                    if (candidate.contains("\"success\"")) return candidate;
                }
            }
        }

        int start = response.indexOf('{'), end = response.lastIndexOf('}');
        if (start >= 0 && end > start) return response.substring(start, end + 1);
        return null;
    }

    private String extractSqlOld(String response) {
        if (response == null) return null;
        Matcher matcher = SQL_PATTERN.matcher(response);
        if (matcher.find()) return matcher.group(1).trim();
        String upper = response.toUpperCase().trim();
        if (upper.startsWith("SELECT")) {
            int semi = response.indexOf(';');
            return semi > 0 ? response.substring(0, semi + 1).trim() : response.trim();
        }
        return null;
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private double resolveTemperature(LlmConfig llmConfig) {
        return llmConfig != null
                ? llmConfig.getTemperature().doubleValue()
                : appConfig.getLlm().getTemperature();
    }

    private DatabaseDialect resolveDialect(Long dataSourceId) {
        return dataSourceRepository.findById(dataSourceId)
                .map(DataSource::getDialect)
                .orElse(DatabaseDialect.POSTGRESQL);
    }

    private String buildTermGlossaryContext(Long dataSourceId) {
        List<TermGlossary> terms = termGlossaryRepository.findByDataSourceIdOrDataSourceIdIsNull(dataSourceId);
        if (terms.isEmpty()) return "(no specific business terms)";
        return terms.stream()
                .map(t -> "- " + t.getTerm() + ": " + t.getDefinition())
                .collect(Collectors.joining("\n"));
    }

    private String loadSqlExamples(Long dataSourceId) {
        List<SqlExample> examples = sqlExampleRepository.findByDataSourceId(dataSourceId);
        if (examples.isEmpty()) return "(no SQL examples)";
        return examples.stream()
                .map(e -> "Q: " + e.getQuestion() + "\nSQL: " + e.getSqlQuery())
                .collect(Collectors.joining("\n\n"));
    }

    private List<ChatMessage> legacyHistoryToConversation(String chatHistory, String currentQuestion) {
        List<ChatMessage> conversation = new ArrayList<>();
        if (chatHistory != null && !chatHistory.isBlank() && !"(no chat history)".equals(chatHistory)) {
            conversation.add(ChatMessage.builder()
                    .role("user")
                    .content("[Previous conversation]\n" + chatHistory)
                    .build());
        }
        conversation.add(ChatMessage.builder().role("user").content(currentQuestion).build());
        return conversation;
    }

    private static String loadResource(String path) {
        try (var is = new org.springframework.core.io.ClassPathResource(path).getInputStream()) {
            return StreamUtils.copyToString(is, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load resource: " + path, e);
        }
    }

    // ---------------------------------------------------------------------------
    // Result DTO
    // ---------------------------------------------------------------------------

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class SqlGenerateResult {
        private String sql;
        private String explanation;
        private String schemaContext;
        private boolean success;
        private String errorMessage;
        private List<String> tables;
    }

    public int getMaxRetry() {
        return maxRetry;
    }
}
