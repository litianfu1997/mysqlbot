package com.example.mysqlbot.service;

import com.example.mysqlbot.model.LlmConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Data analysis and chart recommendation service.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataAnalysisService {

    private final LlmService llmService;
    private final ObjectMapper objectMapper;

    // Cached prompt template
    private String dataAnalysisPrompt;

    @PostConstruct
    public void init() {
        dataAnalysisPrompt = loadResource("prompts/data-analysis.st");
        log.info("DataAnalysisService: prompt template cached ({} chars)", dataAnalysisPrompt.length());
    }

    public AnalysisResult analyze(String question, String sql, List<Map<String, Object>> data) {
        return analyze(question, sql, data, null);
    }

    public AnalysisResult analyze(String question, String sql, List<Map<String, Object>> data, LlmConfig llmConfig) {
        if (data == null || data.isEmpty()) {
            return AnalysisResult.builder()
                    .insight("Query result is empty, cannot analyze.")
                    .chartType("Table")
                    .build();
        }

        String dataJson = truncateDataForPrompt(data);

        String prompt = dataAnalysisPrompt
                .replace("{question}", question)
                .replace("{sql}", sql)
                .replace("{data}", dataJson);

        String llmResponse;
        if (llmConfig != null) {
            llmResponse = llmService.chatWithConfig(null, prompt, 0.3, llmConfig);
        } else {
            llmResponse = llmService.chat(prompt, 0.3);
        }
        log.debug("LLM analysis response:\n{}", llmResponse);

        return parseLlmResponse(llmResponse);
    }

    private String truncateDataForPrompt(List<Map<String, Object>> data) {
        try {
            int limit = Math.min(data.size(), 20);
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
            log.warn("Failed to parse LLM analysis result: {}", response);
            return AnalysisResult.builder()
                    .insight(response)
                    .chartType("Table")
                    .build();
        }
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
    public static class AnalysisResult {
        private String insight;
        private String chartType;
        private String xAxis;
        private String yAxis;
    }
}
