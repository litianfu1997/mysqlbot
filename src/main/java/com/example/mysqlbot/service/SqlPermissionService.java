package com.example.mysqlbot.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 权限控制与动态过滤服务
 * 使用智谱 LLM（zai-sdk）在 SQL 执行前应用行级权限规则
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SqlPermissionService {

    private final ZhipuLlmService zhipuLlmService;

    // 匹配 ```sql ... ``` 代码块
    private static final Pattern SQL_PATTERN = Pattern.compile(
            "```sql\\s*([\\s\\S]+?)\\s*```", Pattern.CASE_INSENSITIVE);

    /**
     * 应用权限规则
     *
     * @param originalSql 原始生成的 SQL
     * @param engine      数据库类型 (MySQL, PostgreSQL)
     * @param filterRule  权限过滤规则 (例如: "dept_id = 1001")
     * @return 修改后的 SQL
     */
    public String applyPermission(String originalSql, String engine, String filterRule) {
        if (filterRule == null || filterRule.trim().isEmpty()) {
            return originalSql;
        }

        log.info("应用 SQL 权限控制: rule='{}'", filterRule);

        String promptTemplate = loadPromptTemplate();
        String prompt = promptTemplate
                .replace("{sql}", originalSql)
                .replace("{engine}", engine)
                .replace("{filter}", filterRule);

        // 调用智谱 LLM（zai-sdk）重写 SQL
        String llmResponse = zhipuLlmService.chat(prompt, 0.1);
        log.debug("权限重写后的 SQL 响应:\n{}", llmResponse);

        String rewrittenSql = extractSql(llmResponse);
        return rewrittenSql != null ? rewrittenSql : originalSql;
    }

    private String extractSql(String response) {
        if (response == null)
            return null;

        Matcher matcher = SQL_PATTERN.matcher(response);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }

        String trimmed = response.trim();
        if (trimmed.toUpperCase().startsWith("SELECT")) {
            return trimmed;
        }

        return null;
    }

    private String loadPromptTemplate() {
        try {
            ClassPathResource resource = new ClassPathResource("prompts/sql-permissions.st");
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("加载 Prompt 模板失败", e);
        }
    }
}
