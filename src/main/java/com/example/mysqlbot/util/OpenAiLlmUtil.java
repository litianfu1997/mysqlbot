package com.example.mysqlbot.util;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * 通用 OpenAI 兼容 HTTP 客户端（兼容 DeepSeek / OpenAI / 通义千问等）
 * 支持 DeepSeek 特有功能：thinking 推理模式、reasoning_content 解析
 */
@Slf4j
public class OpenAiLlmUtil {

    private final RestClient restClient;
    private final String model;

    public OpenAiLlmUtil(String baseUrl, String apiKey, String model) {
        String cleanBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.model = model;
        this.restClient = RestClient.builder()
                .baseUrl(cleanBase)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /** 普通 Chat，不启用 thinking 模式 */
    public String chat(List<Map<String, String>> messages, double temperature) {
        return chat(messages, temperature, null, false);
    }

    /** 普通 Chat，允许覆盖模型名（null 时使用构造时传入的 model） */
    public String chat(List<Map<String, String>> messages, double temperature, String modelOverride) {
        String effectiveModel = (modelOverride != null && !modelOverride.isBlank()) ? modelOverride : this.model;
        ChatRequest request = new ChatRequest();
        request.setModel(effectiveModel);
        request.setTemperature(temperature);
        request.setMessages(messages);

        log.debug("OpenAiLlmUtil request: model={}, messages={}", effectiveModel, messages.size());

        try {
            ChatResponse response = restClient.post()
                    .uri("/chat/completions")
                    .body(request)
                    .retrieve()
                    .body(ChatResponse.class);

            if (response == null || response.getChoices() == null || response.getChoices().isEmpty()) {
                throw new RuntimeException("LLM 返回为空");
            }
            return response.getChoices().get(0).getMessage().getContent();
        } catch (Exception e) {
            log.error("OpenAiLlmUtil request failed: {}", e.getMessage());
            throw new RuntimeException("LLM 调用失败: " + e.getMessage(), e);
        }
    }

    /**
     * Chat 请求
     *
     * @param messages    消息列表
     * @param temperature 温度
     * @param maxTokens   最大 token 数，null 则不传
     * @param thinking    是否启用 DeepSeek thinking（推理）模式
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

        try {
            ChatResponse response = restClient.post()
                    .uri("/chat/completions")
                    .body(request)
                    .retrieve()
                    .body(ChatResponse.class);

            if (response == null || response.getChoices() == null || response.getChoices().isEmpty()) {
                throw new RuntimeException("LLM 返回为空");
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
            log.error("OpenAiLlmUtil request failed: {}", e.getMessage());
            throw new RuntimeException("LLM 调用失败: " + e.getMessage(), e);
        }
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
        /** DeepSeek thinking（推理）模式；标准 OpenAI 不支持此字段，发送时为 null 则不序列化 */
        private ThinkingConfig thinking;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ThinkingConfig {
        private String type;

        public ThinkingConfig(String type) {
            this.type = type;
        }
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
                /** DeepSeek 推理模式下的思考过程 */
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
            /** DeepSeek 推理 token 数 */
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
