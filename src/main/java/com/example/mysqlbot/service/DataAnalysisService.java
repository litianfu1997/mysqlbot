package com.example.mysqlbot.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 数据分析与图表推荐服务
 * 使用智谱 LLM（zai-sdk）根据 SQL 执行结果生成简要分析和图表配置建议
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataAnalysisService {

    private final ZhipuLlmService zhipuLlmService;
    private final ObjectMapper objectMapper;

    /**
     * 生成数据分析和图表推荐
     *
     * @param question 用户问题
     * @param sql      执行的 SQL
     * @param data     查询结果集（List<Map<String, Object>>）
     * @return 分析结果
     */
    public AnalysisResult analyze(String question, String sql, List<Map<String, Object>> data) {
        if (data == null || data.isEmpty()) {
            return AnalysisResult.builder()
                    .insight("查询结果为空，无法进行分析。")
                    .chartType("Table")
                    .build();
        }

        // 限制 Prompt 中放入的数据量，避免 Context Window 溢出
        String dataJson = truncateDataForPrompt(data);

        // 加载 Prompt
        String promptTemplate = loadPromptTemplate();
        String prompt = promptTemplate
                .replace("{question}", question)
                .replace("{sql}", sql)
                .replace("{data}", dataJson);

        // 调用智谱 LLM（zai-sdk）
        String llmResponse = zhipuLlmService.chat(prompt, 0.3);
        log.debug("LLM 分析响应:\n{}", llmResponse);

        // 解析 JSON 响应
        return parseLlmResponse(llmResponse);
    }

    private String truncateDataForPrompt(List<Map<String, Object>> data) {
        try {
            int limit = Math.min(data.size(), 20); // 最多取 20 条样本给 AI 分析
            return objectMapper.writeValueAsString(data.subList(0, limit));
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private AnalysisResult parseLlmResponse(String response) {
        try {
            String cleanerJson = response.replaceAll("```json", "").replaceAll("```", "").trim();
            return objectMapper.readValue(cleanerJson, AnalysisResult.class);
        } catch (JsonProcessingException e) {
            log.warn("解析 LLM 分析结果失败: {}", response);
            return AnalysisResult.builder()
                    .insight(response)
                    .chartType("Table")
                    .build();
        }
    }

    private String loadPromptTemplate() {
        try {
            ClassPathResource resource = new ClassPathResource("prompts/data-analysis.st");
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("加载 Prompt 模板失败", e);
        }
    }

    @Data
    @Builder
    public static class AnalysisResult {
        private String insight;
        private String chartType;
        private String xAxis;
        private String yAxis;
    }
}
