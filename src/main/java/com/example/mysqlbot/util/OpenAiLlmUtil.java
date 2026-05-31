package com.example.mysqlbot.util;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Generic OpenAI-compatible HTTP client (DeepSeek / OpenAI / Tongyi Qianwen etc.)
 * Supports DeepSeek thinking mode and reasoning_content parsing.
 * Configured with connect/read timeouts and retry logic.
 */
@Slf4j
public class OpenAiLlmUtil {

    private static final int MAX_RETRIES = 2;
    private static final long RETRY_DELAY_MS = 1000;

    private final RestClient restClient;
    private final String model;

    public OpenAiLlmUtil(String baseUrl, String apiKey, String model) {
        String cleanBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.model = model;
        this.restClient = RestClient.builder()
                .baseUrl(cleanBase)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .requestFactory(createRequestFactory())
                .build();
    }

    private static ClientHttpRequestFactory createRequestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(90));
        return factory;
    }

    /** Standard chat, no model override */
    public String chat(List<Map<String, String>> messages, double temperature) {
        return chat(messages, temperature, null, false);
    }

    /** Standard chat, with optional model override */
    public String chat(List<Map<String, String>> messages, double temperature, String modelOverride) {
        String effectiveModel = (modelOverride != null && !modelOverride.isBlank()) ? modelOverride : this.model;
        ChatRequest request = new ChatRequest();
        request.setModel(effectiveModel);
        request.setTemperature(temperature);
        request.setMessages(messages);

        log.debug("OpenAiLlmUtil request: model={}, messages={}", effectiveModel, messages.size());
        return executeWithRetry(request);
    }

    /**
     * Full chat request with all options.
     */
    public String chat(List<Map<String, String>> messages, double temperature, Integer maxTokens, boolean thinking) {
        ChatRequest request = new ChatRequest();
        request.setModel(model);
        request.setTemperature(temperature);
        request.setMessages(messages);
        request.setMaxTokens(maxTokens);
        if (thinking) {
            request.setThinking(new ThinkingConfig("enabled"));
        }

        log.debug("OpenAiLlmUtil request: model={}, thinking={}, messages={}", model, thinking, messages.size());
        return executeWithRetry(request);
    }

    private String executeWithRetry(ChatRequest request) {
        Exception lastException = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                ChatResponse response = restClient.post()
                        .uri("/chat/completions")
                        .body(request)
                        .retrieve()
                        .body(ChatResponse.class);

                if (response == null || response.getChoices() == null || response.getChoices().isEmpty()) {
                    throw new RuntimeException("LLM returned empty response");
                }

                ChatResponse.Choice choice = response.getChoices().get(0);
                String reasoningContent = choice.getMessage().getReasoningContent();
                if (reasoningContent != null && !reasoningContent.isBlank()) {
                    log.debug("DeepSeek reasoning_content ({}chars): {}...",
                            reasoningContent.length(),
                            reasoningContent.substring(0, Math.min(200, reasoningContent.length())));
                }

                return choice.getMessage().getContent();
            } catch (Exception e) {
                lastException = e;
                if (attempt < MAX_RETRIES) {
                    long delay = RETRY_DELAY_MS * (attempt + 1);
                    log.warn("LLM request failed (attempt {}/{}), retrying in {}ms: {}",
                            attempt + 1, MAX_RETRIES + 1, delay, e.getMessage());
                    try { Thread.sleep(delay); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("LLM call interrupted", ie);
                    }
                }
            }
        }
        log.error("OpenAiLlmUtil request failed after {} attempts", MAX_RETRIES + 1);
        throw new RuntimeException("LLM call failed: " + lastException.getMessage(), lastException);
    }

    // ===== DTOs =====

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ChatRequest {
        private String model;
        private List<Map<String, String>> messages;
        private double temperature = 0.1;
        @JsonProperty("max_tokens")
        private Integer maxTokens;
        @JsonProperty("stream")
        private boolean stream = false;
        private ThinkingConfig thinking;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ThinkingConfig {
        private String type;
        public ThinkingConfig(String type) { this.type = type; }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ChatResponse {
        private List<Choice> choices;
        private Usage usage;

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class Choice {
            private Message message;
            @JsonProperty("finish_reason")
            private String finishReason;

            @Data
            @JsonIgnoreProperties(ignoreUnknown = true)
            public static class Message {
                private String role;
                private String content;
                @JsonProperty("reasoning_content")
                private String reasoningContent;
            }
        }

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class Usage {
            @JsonProperty("prompt_tokens")
            private int promptTokens;
            @JsonProperty("completion_tokens")
            private int completionTokens;
            @JsonProperty("total_tokens")
            private int totalTokens;
            @JsonProperty("completion_tokens_details")
            private CompletionTokensDetails completionTokensDetails;

            @Data
            @JsonIgnoreProperties(ignoreUnknown = true)
            public static class CompletionTokensDetails {
                @JsonProperty("reasoning_tokens")
                private int reasoningTokens;
            }
        }
    }
}
