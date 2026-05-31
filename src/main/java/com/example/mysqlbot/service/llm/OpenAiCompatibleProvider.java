package com.example.mysqlbot.service.llm;

import com.example.mysqlbot.util.OpenAiLlmUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
public class OpenAiCompatibleProvider implements LlmProvider {

    private final OpenAiLlmUtil util;

    public OpenAiCompatibleProvider(String baseUrl, String apiKey, String defaultModel) {
        String effectiveBaseUrl = (baseUrl != null && !baseUrl.isBlank()) ? baseUrl : "https://api.openai.com/v1";
        this.util = new OpenAiLlmUtil(effectiveBaseUrl, apiKey, defaultModel);
        log.info("OpenAiCompatibleProvider: client initialized baseUrl={}", effectiveBaseUrl);
    }

    @Override
    public String chat(String systemPrompt, String userMessage, double temperature, String modelName) {
        List<Map<String, String>> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(Map.of("role", "system", "content", systemPrompt));
        }
        messages.add(Map.of("role", "user", "content", userMessage));
        log.debug("OpenAiCompatibleProvider chat: model={}, temperature={}", modelName, temperature);
        return util.chat(messages, temperature, modelName);
    }

    @Override
    public String chatWithMessages(List<Map<String, String>> messages, double temperature, String modelName) {
        log.debug("OpenAiCompatibleProvider chatWithMessages: model={}, messages={}", modelName, messages.size());
        return util.chat(messages, temperature, modelName);
    }
}
