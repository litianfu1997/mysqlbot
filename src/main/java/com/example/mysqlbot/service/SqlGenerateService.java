package com.example.mysqlbot.service;

import com.example.mysqlbot.config.AppConfig;
import com.example.mysqlbot.model.DataSource;
import com.example.mysqlbot.model.DatabaseDialect;
import com.example.mysqlbot.model.LlmConfig;
import com.example.mysqlbot.model.TermGlossary;
import com.example.mysqlbot.repository.DataSourceRepository;
import com.example.mysqlbot.repository.TermGlossaryRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * SQL generation service using LLM + RAG to convert natural language to SQL.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SqlGenerateService {

    private final AppConfig appConfig;
    private final RagService ragService;
    private final TermGlossaryRepository termGlossaryRepository;
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

    public SqlGenerateResult generate(String question, Long dataSourceId, String chatHistory) {
        return generate(question, dataSourceId, chatHistory, null);
    }

    public SqlGenerateResult generate(String question, Long dataSourceId, String chatHistory, LlmConfig llmConfig) {
        boolean ragEnabled = appConfig.getRag().isEnabled();

        // Resolve database dialect for this data source
        DatabaseDialect dialect = resolveDialect(dataSourceId);

        // 1. RAG retrieval
        String schemaContext;
        if (ragEnabled) {
            List<VectorStoreService.VectorSearchResult> schemaDocs = ragService.retrieveRelevantSchema(question, dataSourceId);
            schemaContext = ragService.buildSchemaContext(schemaDocs);
            log.debug("RAG retrieved Schema:\n{}", schemaContext);
        } else {
            schemaContext = "(RAG disabled, no Schema indexed)";
            log.debug("RAG disabled, skipping retrieval");
        }

        // 2. Build context
        String termGlossary = buildTermGlossaryContext(dataSourceId);
        String sqlExamples = ragEnabled ? buildSqlExamplesContext(question, dataSourceId) : "(RAG disabled)";

        // 3. Fill prompt template with dialect-aware engine name and quoting rules
        String prompt = sqlGeneratePrompt
                .replace("{dbEngine}", dialect.getDisplayName())
                .replace("{quoteRules}", dialect.getQuotingRules())
                .replace("{schemaContext}", schemaContext)
                .replace("{termGlossary}", termGlossary)
                .replace("{sqlExamples}", sqlExamples)
                .replace("{chatHistory}", chatHistory != null ? chatHistory : "(no chat history)")
                .replace("{question}", question);

        // 4. Call LLM
        String llmResponse;
        double temperature = llmConfig != null ? llmConfig.getTemperature().doubleValue() : appConfig.getLlm().getTemperature();
        if (llmConfig != null) {
            llmResponse = llmService.chatWithConfig(null, prompt, temperature, llmConfig);
        } else {
            llmResponse = llmService.chat(prompt, temperature);
        }
        log.debug("LLM response:\n{}", llmResponse);

        // 5. Extract SQL and explanation
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
                .schemaContext(schemaContext)
                .success(sql != null)
                .build();
    }

    private DatabaseDialect resolveDialect(Long dataSourceId) {
        return dataSourceRepository.findById(dataSourceId)
                .map(DataSource::getDialect)
                .orElse(DatabaseDialect.POSTGRESQL);
    }

    private String extractJson(String response) {
        if (response == null) return null;
        String trimmed = response.trim();
        if (trimmed.startsWith("```json")) {
            int end = trimmed.lastIndexOf("```");
            if (end > 7) return trimmed.substring(7, end).trim();
        } else if (trimmed.startsWith("```")) {
            int end = trimmed.lastIndexOf("```");
            if (end > 3) return trimmed.substring(3, end).trim();
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

    private String buildTermGlossaryContext(Long dataSourceId) {
        List<TermGlossary> terms = termGlossaryRepository.findByDataSourceIdOrDataSourceIdIsNull(dataSourceId);
        if (terms.isEmpty()) return "(no specific business terms)";
        return terms.stream()
                .map(t -> "- " + t.getTerm() + ": " + t.getDefinition())
                .collect(Collectors.joining("\n"));
    }

    private String buildSqlExamplesContext(String question, Long dataSourceId) {
        List<VectorStoreService.VectorSearchResult> examples = ragService.retrieveSimilarExamples(question, dataSourceId);
        return ragService.buildExamplesContext(examples);
    }

    private static String loadResource(String path) {
        try (var is = new org.springframework.core.io.ClassPathResource(path).getInputStream()) {
            return StreamUtils.copyToString(is, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load resource: " + path, e);
        }
    }

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
