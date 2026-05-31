package com.example.mysqlbot.service.llm;

import com.example.mysqlbot.util.OpenAiLlmUtil;
import com.example.mysqlbot.util.OpenAiLlmUtil.ChatResult;

import java.util.List;
import java.util.Map;

public interface LlmProvider {

    String chat(String systemPrompt, String userMessage, double temperature, String modelName);

    /**
     * Multi-turn chat using structured message list.
     */
    default String chatWithMessages(List<Map<String, String>> messages, double temperature, String modelName) {
        String combined = messages.stream()
                .map(m -> m.get("role") + ": " + m.get("content"))
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
        return chat(null, combined, temperature, modelName);
    }

    /**
     * Streaming multi-turn chat. Delivers tokens in real-time via callback.
     * Returns the full accumulated response text.
     */
    default String chatStreamWithMessages(List<Map<String, String>> messages, double temperature,
                                          String modelName, OpenAiLlmUtil.StreamCallback callback) {
        // Default fallback: synchronous call, deliver full text as a single content token
        String result = chatWithMessages(messages, temperature, modelName);
        callback.onToken("content", result);
        return result;
    }

    /**
     * Chat with tools/function calling support.
     * Returns a structured result containing content and tool calls.
     */
    default ChatResult chatWithMessagesAndTools(
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            double temperature, String modelName) {
        throw new UnsupportedOperationException("chatWithMessagesAndTools not implemented");
    }

    default void close() {}
}
