package com.example.mysqlbot.service;

import com.example.mysqlbot.model.LlmConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Suggested follow-up question service.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SuggestQuestionService {

    private final LlmService llmService;
    private final ObjectMapper objectMapper;

    // Cached prompt template
    private String suggestQuestionsPrompt;

    @PostConstruct
    public void init() {
        suggestQuestionsPrompt = loadResource("prompts/suggest-questions.st");
        log.info("SuggestQuestionService: prompt template cached ({} chars)", suggestQuestionsPrompt.length());
    }

    public List<String> suggest(String question, String sql) {
        return suggest(question, sql, null);
    }

    public List<String> suggest(String question, String sql, LlmConfig llmConfig) {
        String prompt = suggestQuestionsPrompt
                .replace("{question}", question)
                .replace("{sql}", sql != null ? sql : "(no SQL)");

        String llmResponse;
        if (llmConfig != null) {
            llmResponse = llmService.chatWithConfig(null, prompt, 0.5, llmConfig);
        } else {
            llmResponse = llmService.chat(prompt, 0.5);
        }
        log.debug("LLM suggest questions response:\n{}", llmResponse);

        return parseLlmResponse(llmResponse);
    }

    private List<String> parseLlmResponse(String response) {
        try {
            String cleanerJson = response.replaceAll("```json", "").replaceAll("```", "").trim();
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
            return list.isEmpty() ? List.of("What is the data trend?", "Can we compare by month?", "What are the anomalies?") : list;
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
