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
 * 自定义 OpenAI 兼容 HTTP 客户端
 * 使用用户提供的 base URL 原样发送请求（不附加 /v1），
 * 只在末尾拼接 /chat/completions。
 * <p>
 * 用户填写的 Base URL 示例:
 * https://api.example.com/v4 -> 请求 https://api.example.com/v4/chat/completions
 * https://api.openai.com/v1 -> 请求 https://api.openai.com/v1/chat/completions
 */
@Slf4j
public class CustomOpenAiClient {

    private final RestClient restClient;
    private final String model;

    public CustomOpenAiClient(String baseUrl, String apiKey, String model) {
        // 去掉末尾斜杠
        String cleanBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;

        this.model = model;
        this.restClient = RestClient.builder()
                .baseUrl(cleanBase)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * 发送 Chat 请求，返回 assistant 回复文本
     */
    public String chat(String userMessage, double temperature) {
        ChatRequest request = new ChatRequest();
        request.setModel(model);
        request.setTemperature(temperature);
        request.setMessages(List.of(
                Map.of("role", "user", "content", userMessage)));

        log.debug("Sending chat request to model={}", model);

        ChatResponse response = restClient.post()
                .uri("/chat/completions") // 直接拼在 baseUrl 后，不加 /v1
                .body(request)
                .retrieve()
                .body(ChatResponse.class);

        if (response == null || response.getChoices() == null || response.getChoices().isEmpty()) {
            throw new RuntimeException("LLM 返回为空");
        }
        return response.getChoices().get(0).getMessage().getContent();
    }

    // ===== 请求/响应结构 =====

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ChatRequest {
        private String model;
        private List<Map<String, String>> messages;
        private double temperature = 0.1;
        @JsonProperty("stream")
        private boolean stream = false;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ChatResponse {
        private List<Choice> choices;

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class Choice {
            private Message message;

            @Data
            @JsonIgnoreProperties(ignoreUnknown = true)
            public static class Message {
                private String role;
                private String content;
            }
        }
    }
}
