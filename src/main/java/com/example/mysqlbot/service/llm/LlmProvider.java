package com.example.mysqlbot.service.llm;

import java.util.List;
import java.util.Map;

public interface LlmProvider {

    String chat(String systemPrompt, String userMessage, double temperature, String modelName);

    /**
     * Multi-turn chat using structured message list.
     * Each message is a map with "role" and "content" keys.
     */
    default String chatWithMessages(List<Map<String, String>> messages, double temperature, String modelName) {
        // Default: concatenate messages into a single user message
        String combined = messages.stream()
                .map(m -> m.get("role") + ": " + m.get("content"))
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
        return chat(null, combined, temperature, modelName);
    }

    default void close() {}
}
