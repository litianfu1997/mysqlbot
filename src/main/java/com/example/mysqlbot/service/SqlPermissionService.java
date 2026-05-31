package com.example.mysqlbot.service;

import com.example.mysqlbot.model.LlmConfig;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL permission control service.
 * Uses LLM to rewrite SQL with row-level permission rules before execution.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SqlPermissionService {

    private final LlmService llmService;

    private static final Pattern SQL_PATTERN = Pattern.compile(
            "```sql\\s*([\\s\\S]+?)\\s*```", Pattern.CASE_INSENSITIVE);

    // Cached prompt template
    private String permissionPrompt;

    @PostConstruct
    public void init() {
        permissionPrompt = loadResource("prompts/sql-permissions.st");
        log.info("SqlPermissionService: prompt template cached ({} chars)", permissionPrompt.length());
    }

    public String applyPermission(String originalSql, String engine, String filterRule) {
        return applyPermission(originalSql, engine, filterRule, null);
    }

    public String applyPermission(String originalSql, String engine, String filterRule, LlmConfig llmConfig) {
        if (filterRule == null || filterRule.trim().isEmpty()) return originalSql;

        log.info("Applying SQL permission control: rule='{}'", filterRule);

        String prompt = permissionPrompt
                .replace("{sql}", originalSql)
                .replace("{engine}", engine)
                .replace("{filter}", filterRule);

        String llmResponse;
        if (llmConfig != null) {
            llmResponse = llmService.chatWithConfig(null, prompt, 0.1, llmConfig);
        } else {
            llmResponse = llmService.chat(prompt, 0.1);
        }

        String rewrittenSql = extractSql(llmResponse);
        return rewrittenSql != null ? rewrittenSql : originalSql;
    }

    private String extractSql(String response) {
        if (response == null) return null;
        Matcher matcher = SQL_PATTERN.matcher(response);
        if (matcher.find()) return matcher.group(1).trim();
        String trimmed = response.trim();
        if (trimmed.toUpperCase().startsWith("SELECT")) return trimmed;
        return null;
    }

    private static String loadResource(String path) {
        try (var is = new org.springframework.core.io.ClassPathResource(path).getInputStream()) {
            return StreamUtils.copyToString(is, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load resource: " + path, e);
        }
    }
}
