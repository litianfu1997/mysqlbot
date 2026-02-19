package com.example.mysqlbot.service;

import com.example.mysqlbot.config.AppConfig;
import com.example.mysqlbot.model.TermGlossary;
import com.example.mysqlbot.repository.TermGlossaryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * SQL 生成服务
 * 使用智谱 LLM（zai-sdk）将自然语言转换为 SQL
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SqlGenerateService {

    private final AppConfig appConfig;
    private final RagService ragService;
    private final TermGlossaryRepository termGlossaryRepository;
    private final ZhipuLlmService zhipuLlmService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Value("${mysqlbot.sql.max-retry:3}")
    private int maxRetry;

    // 匹配 ```sql ... ``` 代码块中的 SQL
    private static final Pattern SQL_PATTERN = Pattern.compile(
            "```sql\\s*([\\s\\S]+?)\\s*```", Pattern.CASE_INSENSITIVE);

    /**
     * 根据用户问题和数据源生成 SQL
     */
    public SqlGenerateResult generate(String question, Long dataSourceId, String chatHistory) {
        boolean ragEnabled = appConfig.getRag().isEnabled();

        // 1. RAG 检索相关 Schema（可关闭）
        String schemaContext;
        if (ragEnabled) {
            List<VectorStoreService.VectorSearchResult> schemaDocs = ragService.retrieveRelevantSchema(question,
                    dataSourceId);
            schemaContext = ragService.buildSchemaContext(schemaDocs);
            log.debug("RAG 检索到的 Schema:\n{}", schemaContext);
        } else {
            schemaContext = "（RAG 已关闭，未检索 Schema）";
            log.debug("RAG 已关闭，跳过向量检索");
        }

        // 2. 获取业务术语和参考示例
        String termGlossary = buildTermGlossaryContext(dataSourceId);
        String sqlExamples = ragEnabled ? buildSqlExamplesContext(question, dataSourceId) : "（RAG 已关闭）";

        // 3. 加载 Prompt 模板并填充
        String promptTemplate = loadPromptTemplate();
        String prompt = promptTemplate
                .replace("{schemaContext}", schemaContext)
                .replace("{termGlossary}", termGlossary)
                .replace("{sqlExamples}", sqlExamples)
                .replace("{chatHistory}", chatHistory != null ? chatHistory : "（无历史对话）")
                .replace("{question}", question);

        // 4. 调用智谱 LLM（zai-sdk）
        String llmResponse = zhipuLlmService.chat(prompt, appConfig.getLlm().getTemperature());
        log.debug("LLM 响应:\n{}", llmResponse);

        // 5. 提取 SQL 和 解释
        String sql = null;
        String explanation = llmResponse;

        try {
            // 尝试解析 JSON
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

        // 如果 JSON 解析失败或没提取到 SQL，尝试回退到旧的提取逻辑
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

    /**
     * 尝试提取 JSON 字符串（处理可能存在的 Markdown 代码块标记）
     */
    private String extractJson(String response) {
        if (response == null)
            return null;
        String trimmed = response.trim();
        if (trimmed.startsWith("```json")) {
            int end = trimmed.lastIndexOf("```");
            if (end > 7) {
                return trimmed.substring(7, end).trim();
            }
        } else if (trimmed.startsWith("```")) { // 有些时候 LLM 可能只写 ```
            int end = trimmed.lastIndexOf("```");
            if (end > 3) {
                return trimmed.substring(3, end).trim();
            }
        }
        // 尝试直接查找 { ... }
        int start = response.indexOf('{');
        int end = response.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return response.substring(start, end + 1);
        }
        return null;
    }

    /**
     * 旧的提取逻辑（正则匹配 SQL 代码块）
     */
    private String extractSqlOld(String response) {
        if (response == null)
            return null;

        // 尝试从 ```sql ... ``` 代码块中提取
        Matcher matcher = SQL_PATTERN.matcher(response);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }

        // 尝试直接匹配 SELECT 语句
        String upper = response.toUpperCase().trim();
        if (upper.startsWith("SELECT")) {
            int semicolonIdx = response.indexOf(';');
            return semicolonIdx > 0 ? response.substring(0, semicolonIdx + 1).trim() : response.trim();
        }

        return null;
    }

    private String loadPromptTemplate() {
        try {
            ClassPathResource resource = new ClassPathResource("prompts/sql-generate.st");
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("加载 Prompt 模板失败", e);
        }
    }

    private String buildTermGlossaryContext(Long dataSourceId) {
        List<TermGlossary> terms = termGlossaryRepository.findByDataSourceIdOrDataSourceIdIsNull(dataSourceId);
        if (terms.isEmpty()) {
            return "（无特定业务术语）";
        }
        return terms.stream()
                .map(t -> "- " + t.getTerm() + ": " + t.getDefinition())
                .collect(Collectors.joining("\n"));
    }

    private String buildSqlExamplesContext(String question, Long dataSourceId) {
        List<VectorStoreService.VectorSearchResult> examples = ragService.retrieveSimilarExamples(question,
                dataSourceId);
        return ragService.buildExamplesContext(examples);
    }

    /**
     * SQL 生成结果
     */
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
