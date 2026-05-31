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

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * SQL generation service using LLM + RAG to convert natural language to SQL.
 * Uses structured multi-turn messages (system/user/assistant array) as required by DeepSeek's
 * multi-round chat API — see https://api-docs.deepseek.com/zh-cn/guides/multi_round_chat
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SqlGenerateService {

    private final AppConfig appConfig;
    private final RagService ragService;
    private final TermGlossaryRepository termGlossaryRepository;
    private final com.example.mysqlbot.repository.SqlExampleRepository sqlExampleRepository;
    private final DataSourceRepository dataSourceRepository;
    private final LlmService llmService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

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

    /**
     * Synchronous SQL generation.
     *
     * @param question     current user question (used for RAG retrieval)
     * @param dataSourceId target data source
     * @param conversation recent chat history as ChatMessage list; the current user question
     *                     must already be appended as the last element by the caller
     * @param llmConfig    LLM config to use (null → global default)
     */
    public SqlGenerateResult generate(String question, Long dataSourceId,
                                      List<ChatMessage> conversation, LlmConfig llmConfig) {
        List<Map<String, String>> messages = buildMessages(question, dataSourceId, conversation);
        double temperature = llmConfig != null
                ? llmConfig.getTemperature().doubleValue()
                : appConfig.getLlm().getTemperature();

        String llmResponse = llmService.chatWithMessages(messages, temperature, llmConfig);
        log.debug("LLM response:\n{}", llmResponse);
        return parseLlmResponse(llmResponse, dataSourceId);
    }

    /**
     * Streaming SQL generation. Streams LLM tokens (thinking + content) via callback,
     * then parses the accumulated response to extract SQL.
     */
    public SqlGenerateResult generateStream(String question, Long dataSourceId,
                                            List<ChatMessage> conversation,
                                            LlmConfig llmConfig, boolean thinking,
                                            OpenAiLlmUtil.StreamCallback tokenCallback) {
        List<Map<String, String>> messages = buildMessages(question, dataSourceId, conversation);
        double temperature = llmConfig != null
                ? llmConfig.getTemperature().doubleValue()
                : appConfig.getLlm().getTemperature();

        String llmResponse = llmService.chatStreamWithMessages(messages, temperature, llmConfig, thinking, tokenCallback);
        log.debug("LLM stream response complete ({} chars)", llmResponse.length());
        return parseLlmResponse(llmResponse, dataSourceId);
    }

    // ---------------------------------------------------------------------------
    // Backward-compatible overloads (String chatHistory) — kept for any callers
    // outside the main chat flow; internally converts to a single user message.
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

    /**
     * Builds the messages array for the LLM:
     * <pre>
     *   [ system: static instructions + schema context,
     *     user:      history turn 1,
     *     assistant: history turn 1 (reconstructed JSON),
     *     ...,
     *     user:      current question ]   ← last element of conversation
     * </pre>
     */
    private List<Map<String, String>> buildMessages(String question, Long dataSourceId,
                                                    List<ChatMessage> conversation) {
        boolean ragEnabled = appConfig.getRag().isEnabled();
        DatabaseDialect dialect = resolveDialect(dataSourceId);

        // RAG retrieval (uses question for similarity search)
        String schemaContext;
        Set<String> involvedTableNames;
        if (ragEnabled) {
            List<VectorStoreService.VectorSearchResult> schemaDocs =
                    ragService.retrieveRelevantSchema(question, dataSourceId);
            // 图扩展：补充关联表
            schemaDocs = ragService.expandWithRelations(dataSourceId, schemaDocs);
            schemaContext = ragService.buildSchemaContext(schemaDocs);
            // 提取所有涉及的表名，用于构建关系上下文
            involvedTableNames = schemaDocs.stream()
                    .map(doc -> (String) doc.getMetadata().getOrDefault("tableName", ""))
                    .filter(s -> !s.isBlank())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            log.debug("RAG retrieved schema (after graph expansion):\n{}", schemaContext);
        } else {
            schemaContext = loadFullSchema(dataSourceId);
            involvedTableNames = null; // RAG 关闭时加载全部关系
            log.debug("RAG disabled, using full schema");
        }

        String relationContext = ragService.buildRelationContext(dataSourceId, involvedTableNames);
        String termGlossary = buildTermGlossaryContext(dataSourceId);
        String sqlExamples = ragEnabled
                ? buildSqlExamplesContext(question, dataSourceId)
                : loadSqlExamples(dataSourceId);

        // System message: static instructions with schema context
        String systemContent = sqlGeneratePrompt
                .replace("{dbEngine}", dialect.getDisplayName())
                .replace("{quoteRules}", dialect.getQuotingRules())
                .replace("{schemaContext}", schemaContext)
                .replace("{relationContext}", relationContext)
                .replace("{termGlossary}", termGlossary)
                .replace("{sqlExamples}", sqlExamples);

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemContent));

        // History turns: user / assistant alternating
        if (conversation != null) {
            for (ChatMessage msg : conversation) {
                if ("user".equals(msg.getRole())) {
                    Map<String, String> m = new HashMap<>();
                    m.put("role", "user");
                    m.put("content", msg.getContent() != null ? msg.getContent() : "");
                    messages.add(m);
                } else if ("assistant".equals(msg.getRole())) {
                    Map<String, String> m = new HashMap<>();
                    m.put("role", "assistant");
                    m.put("content", reconstructAssistantJson(msg));
                    messages.add(m);
                }
            }
        }

        log.debug("buildMessages: system + {} history turns, last role={}",
                messages.size() - 1,
                messages.isEmpty() ? "none" : messages.get(messages.size() - 1).get("role"));
        return messages;
    }

    /**
     * Reconstructs the assistant's previous response as the same JSON format that the model
     * is expected to produce, so the model can reference its own prior output clearly.
     * - Successful turn (has SQL): {"success":true,"sql":"...","brief":"..."}
     * - Failed turn             : {"success":false,"message":"..."}
     */
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
            log.warn("reconstructAssistantJson failed, falling back to raw content: {}", e.getMessage());
            return msg.getContent() != null ? msg.getContent() : "";
        }
    }

    // ---------------------------------------------------------------------------
    // Response parsing (unchanged)
    // ---------------------------------------------------------------------------

    private SqlGenerateResult parseLlmResponse(String llmResponse, Long dataSourceId) {
        String sql = null;
        String explanation = llmResponse;

        try {
            String jsonContent = extractJson(llmResponse);
            if (jsonContent != null) {
                com.fasterxml.jackson.databind.JsonNode rootNode = objectMapper.readTree(jsonContent);
                if (rootNode.has("success") && rootNode.get("success").asBoolean()) {
                    if (rootNode.has("sql")) {
                        sql = rootNode.get("sql").asText();
                    }
                    if (rootNode.has("brief")) {
                        explanation = rootNode.get("brief").asText();
                    }
                } else if (rootNode.has("message")) {
                    explanation = rootNode.get("message").asText();
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse LLM response as JSON, falling back to regex: {}", e.getMessage());
        }

        if (sql == null) {
            sql = extractSqlOld(llmResponse);
        }

        return SqlGenerateResult.builder()
                .sql(sql)
                .explanation(explanation)
                .success(sql != null)
                .build();
    }

    private String extractJson(String response) {
        if (response == null) return null;
        String trimmed = response.trim();

        // Try to extract from ```json ... ``` blocks first (find the last one)
        java.util.regex.Pattern jsonBlockPattern = java.util.regex.Pattern.compile(
                "```(?:json)?\\s*\\n?(\\{[\\s\\S]*?\\})\\s*```");
        java.util.regex.Matcher blockMatcher = jsonBlockPattern.matcher(trimmed);
        String lastBlock = null;
        while (blockMatcher.find()) {
            lastBlock = blockMatcher.group(1).trim();
        }
        if (lastBlock != null) return lastBlock;

        // Fallback: find all top-level JSON objects by balanced brace counting
        for (int i = 0; i < trimmed.length(); i++) {
            if (trimmed.charAt(i) == '{') {
                int depth = 1;
                int j = i + 1;
                while (j < trimmed.length() && depth > 0) {
                    char c = trimmed.charAt(j);
                    if (c == '{') depth++;
                    else if (c == '}') depth--;
                    j++;
                }
                if (depth == 0) {
                    String candidate = trimmed.substring(i, j);
                    if (candidate.contains("\"success\"")) {
                        return candidate;
                    }
                }
            }
        }

        int start = response.indexOf('{');
        int end = response.lastIndexOf('}');
        if (start >= 0 && end > start) return response.substring(start, end + 1);
        return null;
    }

    private String extractSqlOld(String response) {
        if (response == null) return null;
        Matcher matcher = SQL_PATTERN.matcher(response);
        if (matcher.find()) return matcher.group(1).trim();
        String upper = response.toUpperCase().trim();
        if (upper.startsWith("SELECT")) {
            int semicolonIdx = response.indexOf(';');
            return semicolonIdx > 0 ? response.substring(0, semicolonIdx + 1).trim() : response.trim();
        }
        return null;
    }

    // ---------------------------------------------------------------------------
    // Schema / glossary / example helpers (unchanged)
    // ---------------------------------------------------------------------------

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

    private String buildSqlExamplesContext(String question, Long dataSourceId) {
        List<VectorStoreService.VectorSearchResult> examples =
                ragService.retrieveSimilarExamples(question, dataSourceId);
        return ragService.buildExamplesContext(examples);
    }

    private String loadFullSchema(Long dataSourceId) {
        try {
            DataSource ds = dataSourceRepository.findById(dataSourceId).orElse(null);
            if (ds == null) return "(no data source found)";
            String url = ds.getDialect().buildJdbcUrl(ds.getHost(), ds.getPort(), ds.getDbName());
            StringBuilder sb = new StringBuilder();
            try (Connection conn = DriverManager.getConnection(url, ds.getUsername(), ds.getPassword())) {
                DatabaseMetaData meta = conn.getMetaData();
                ResultSet tables = meta.getTables(null, "public", "%", new String[]{"TABLE"});
                while (tables.next()) {
                    String tableName = tables.getString("TABLE_NAME");
                    sb.append("Table: ").append(tableName).append(" (\n");
                    ResultSet cols = meta.getColumns(null, "public", tableName, null);
                    boolean first = true;
                    while (cols.next()) {
                        if (!first) sb.append(",\n");
                        sb.append("  ").append(cols.getString("COLUMN_NAME"))
                          .append(" ").append(cols.getString("TYPE_NAME"));
                        String comment = cols.getString("REMARKS");
                        if (comment != null && !comment.isBlank()) sb.append(" -- ").append(comment);
                        first = false;
                    }
                    sb.append("\n)\n");
                }
            }
            return sb.length() > 0 ? sb.toString() : "(no tables found)";
        } catch (Exception e) {
            log.warn("Failed to load full schema: {}", e.getMessage());
            return "(schema load failed: " + e.getMessage() + ")";
        }
    }

    private String loadSqlExamples(Long dataSourceId) {
        List<SqlExample> examples = sqlExampleRepository.findByDataSourceId(dataSourceId);
        if (examples.isEmpty()) return "(no SQL examples)";
        return examples.stream()
                .map(e -> "Q: " + e.getQuestion() + "\nSQL: " + e.getSqlQuery())
                .collect(Collectors.joining("\n\n"));
    }

    // ---------------------------------------------------------------------------
    // Legacy helper: convert flat history string → minimal ChatMessage list
    // ---------------------------------------------------------------------------

    /**
     * Converts the legacy flat history string into a ChatMessage list ending with
     * a user message for the current question.  Used only by the backward-compatible
     * overloads that still accept a String chatHistory.
     */
    private List<ChatMessage> legacyHistoryToConversation(String chatHistory, String currentQuestion) {
        List<ChatMessage> conversation = new ArrayList<>();
        if (chatHistory != null && !chatHistory.isBlank() && !"(no chat history)".equals(chatHistory)) {
            // The legacy format is "User: ...\nAssistant: ...\n..."
            // We treat the whole block as a single synthetic user context message to keep the
            // contract valid (last message must be user), then append current question.
            ChatMessage historyCtx = ChatMessage.builder()
                    .role("user")
                    .content("[Previous conversation]\n" + chatHistory)
                    .build();
            conversation.add(historyCtx);
        }
        ChatMessage current = ChatMessage.builder()
                .role("user")
                .content(currentQuestion)
                .build();
        conversation.add(current);
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
    }
}
