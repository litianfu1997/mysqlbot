package com.example.mysqlbot.util;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Generic OpenAI-compatible HTTP client (DeepSeek / OpenAI / Tongyi Qianwen etc.)
 * Supports DeepSeek thinking mode, reasoning_content parsing, and SSE streaming.
 */
@Slf4j
public class OpenAiLlmUtil {

    private static final int MAX_RETRIES = 2;
    private static final long RETRY_DELAY_MS = 1000;
    private static final ObjectMapper STREAM_MAPPER = new ObjectMapper();

    private final RestClient restClient;
    private final HttpClient streamClient;
    private final String baseUrl;
    private final String apiKey;
    private final String model;

    public OpenAiLlmUtil(String baseUrl, String apiKey, String model) {
        String cleanBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.baseUrl = cleanBase;
        this.apiKey = apiKey;
        this.model = model;
        this.restClient = RestClient.builder()
                .baseUrl(cleanBase)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .requestFactory(createRequestFactory())
                .build();
        this.streamClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
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
        return chat(messages, temperature, null, false, null);
    }

    /** Standard chat, with optional model override */
    public String chat(List<Map<String, String>> messages, double temperature, String modelOverride) {
        return chat(messages, temperature, modelOverride, null);
    }

    /**
     * Full chat request with all options including tools.
     */
    public String chat(List<Map<String, String>> messages, double temperature, String modelOverride, List<Map<String, Object>> tools) {
        String effectiveModel = (modelOverride != null && !modelOverride.isBlank()) ? modelOverride : this.model;
        ChatRequest request = new ChatRequest();
        request.setModel(effectiveModel);
        request.setTemperature(temperature);
        request.setMessages(messages);
        request.setTools(tools);

        log.debug("OpenAiLlmUtil request: model={}, messages={}, tools={}", effectiveModel, messages.size(), tools != null ? tools.size() : 0);
        return executeWithRetry(request).getContent();
    }

    /**
     * Chat with tools/function calling support.
     * Returns a structured result containing content and tool calls.
     * Supports messages with tool results (List<Map<String, Object>>).
     */
    public ChatResult chatWithMessagesAndTools(List<Map<String, Object>> messages, double temperature,
                                                String modelOverride, List<Map<String, Object>> tools) {
        String effectiveModel = (modelOverride != null && !modelOverride.isBlank()) ? modelOverride : this.model;
        ChatRequest request = new ChatRequest();
        request.setModel(effectiveModel);
        request.setTemperature(temperature);
        request.setMessages(messages);
        request.setTools(tools);

        log.debug("OpenAiLlmUtil request: model={}, messages={}, tools={}", effectiveModel, messages.size(), tools != null ? tools.size() : 0);
        return executeWithRetry(request);
    }

    /**
     * Full chat request with all options.
     */
    public String chat(List<Map<String, String>> messages, double temperature, Integer maxTokens, boolean thinking) {
        return chat(messages, temperature, maxTokens, thinking, null);
    }

    /**
     * Full chat request with all options including tools.
     */
    public String chat(List<Map<String, String>> messages, double temperature, Integer maxTokens, boolean thinking, List<Map<String, Object>> tools) {
        ChatRequest request = new ChatRequest();
        request.setModel(model);
        request.setTemperature(temperature);
        request.setMessages(messages);
        request.setMaxTokens(maxTokens);
        request.setTools(tools);
        if (thinking) {
            request.setThinking(new ThinkingConfig("enabled"));
        }

        log.debug("OpenAiLlmUtil request: model={}, thinking={}, messages={}, tools={}", model, thinking, messages.size(), tools != null ? tools.size() : 0);
        return executeWithRetry(request).getContent();
    }

    /**
     * Streaming chat. Calls the OpenAI-compatible API with stream=true and delivers
     * tokens in real-time via the provided callback.
     *
     * @param messages    chat messages
     * @param temperature sampling temperature
     * @param modelOverride optional model name override
     * @param callback    receives each token with type "thinking", "content", or "tool_calls"
     * @return the full accumulated response text
     */
    public String chatStream(List<Map<String, String>> messages, double temperature,
                             String modelOverride, StreamCallback callback) {
        return chatStream(messages, temperature, modelOverride, callback, null);
    }

    /**
     * Streaming chat with tools support. Calls the OpenAI-compatible API with stream=true and delivers
     * tokens in real-time via the provided callback.
     *
     * @param messages    chat messages
     * @param temperature sampling temperature
     * @param modelOverride optional model name override
     * @param callback    receives each token with type "thinking", "content", or "tool_calls"
     * @param tools       optional tools/function calling definitions
     * @return the full accumulated response text
     */
    public String chatStream(List<Map<String, String>> messages, double temperature,
                             String modelOverride, StreamCallback callback, List<Map<String, Object>> tools) {
        String effectiveModel = (modelOverride != null && !modelOverride.isBlank()) ? modelOverride : this.model;
        ChatRequest request = new ChatRequest();
        request.setModel(effectiveModel);
        request.setTemperature(temperature);
        request.setMessages(messages);
        request.setStream(true);
        request.setTools(tools);

        log.debug("OpenAiLlmUtil stream request: model={}, messages={}, tools={}", effectiveModel, messages.size(), tools != null ? tools.size() : 0);

        try {
            String body = STREAM_MAPPER.writeValueAsString(request);
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .timeout(Duration.ofMinutes(3))
                    .build();

            HttpResponse<java.io.InputStream> httpResp = streamClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            if (httpResp.statusCode() != 200) {
                String errBody = new String(httpResp.body().readAllBytes(), StandardCharsets.UTF_8);
                throw new RuntimeException("LLM stream HTTP " + httpResp.statusCode() + ": " + errBody);
            }

            StringBuilder fullContent = new StringBuilder();
            StringBuilder fullThinking = new StringBuilder();
            BufferedReader reader = new BufferedReader(new InputStreamReader(httpResp.body(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if ("[DONE]".equals(data)) break;

                try {
                    JsonNode chunk = STREAM_MAPPER.readTree(data);
                    JsonNode choices = chunk.get("choices");
                    if (choices == null || choices.isEmpty()) continue;
                    JsonNode delta = choices.get(0).get("delta");
                    if (delta == null) continue;

                    // DeepSeek reasoning_content
                    JsonNode reasoningNode = delta.get("reasoning_content");
                    if (reasoningNode != null && !reasoningNode.isNull() && !reasoningNode.asText().isEmpty()) {
                        String token = reasoningNode.asText();
                        fullThinking.append(token);
                        callback.onToken("thinking", token);
                    }

                    // Standard content
                    JsonNode contentNode = delta.get("content");
                    if (contentNode != null && !contentNode.isNull() && !contentNode.asText().isEmpty()) {
                        String token = contentNode.asText();
                        fullContent.append(token);
                        callback.onToken("content", token);
                    }

                    // Tool calls streaming detection
                    JsonNode toolCallsNode = delta.get("tool_calls");
                    if (toolCallsNode != null && toolCallsNode.isArray()) {
                        String toolCallsJson = STREAM_MAPPER.writeValueAsString(toolCallsNode);
                        callback.onToken("tool_calls", toolCallsJson);
                    }
                } catch (Exception e) {
                    log.trace("Failed to parse SSE chunk: {}", data, e);
                }
            }

            // If there was thinking content but no regular content, return thinking
            if (fullContent.isEmpty() && fullThinking.length() > 0) {
                return fullThinking.toString();
            }
            return fullContent.toString();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("LLM stream call failed: " + e.getMessage(), e);
        }
    }

    private ChatResult executeWithRetry(ChatRequest request) {
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
                ChatResponse.Choice.Message message = choice.getMessage();
                String reasoningContent = message.getReasoningContent();
                if (reasoningContent != null && !reasoningContent.isBlank()) {
                    log.debug("DeepSeek reasoning_content ({}chars): {}...",
                            reasoningContent.length(),
                            reasoningContent.substring(0, Math.min(200, reasoningContent.length())));
                }

                // Check for tool calls
                List<ChatResponse.Choice.ToolCall> toolCalls = message.getToolCalls();
                if (toolCalls != null && !toolCalls.isEmpty()) {
                    return new ChatResult(message.getContent(), toolCalls, choice.getFinishReason());
                }

                return ChatResult.fromContent(message.getContent());
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

    // ===== Streaming callback interface =====

    public interface StreamCallback {
        void onToken(String type, String token);
    }

    // ===== DTOs =====

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ChatRequest {
        private String model;
        private Object messages; // Accept both List<Map<String, String>> and List<Map<String, Object>>
        private double temperature = 0.1;
        @JsonProperty("max_tokens")
        private Integer maxTokens;
        @JsonProperty("stream")
        private boolean stream = false;
        private ThinkingConfig thinking;
        private List<Map<String, Object>> tools;
        @JsonProperty("tool_choice")
        private String toolChoice;
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
                @JsonProperty("tool_calls")
                private List<ToolCall> toolCalls;
            }

            @Data
            @JsonIgnoreProperties(ignoreUnknown = true)
            public static class ToolCall {
                private String id;
                private String type;
                private FunctionCall function;

                @Data
                @JsonIgnoreProperties(ignoreUnknown = true)
                public static class FunctionCall {
                    private String name;
                    private String arguments;
                }
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

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ChatResult {
        private String content;
        private List<ChatResponse.Choice.ToolCall> toolCalls;
        @JsonProperty("finish_reason")
        private String finishReason;

        public ChatResult(String content, List<ChatResponse.Choice.ToolCall> toolCalls, String finishReason) {
            this.content = content;
            this.toolCalls = toolCalls;
            this.finishReason = finishReason;
        }

        public static ChatResult fromContent(String content) {
            return new ChatResult(content, null, "stop");
        }
    }
}
