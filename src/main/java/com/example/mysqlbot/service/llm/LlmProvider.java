package com.example.mysqlbot.service.llm;

public interface LlmProvider {

    String chat(String systemPrompt, String userMessage, double temperature, String modelName);

    default void close() {}
}
