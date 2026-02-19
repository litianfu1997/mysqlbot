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
 * 通用 OpenAI 兼容 HTTP 客户端
 * 用于非智谱模型（如 DeepSeek, OpenAI, 通义千问等）
 */
@Slf4j
public class OpenAiLlmUtil {

    private final RestClient restClient;
    private final String model;

    public OpenAiLlmUtil(String baseUrl, String apiKey, String model) {
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
     * 发送 Chat 请求
     */
    public String chat(List<Map<String, String>> messages, double temperature) {
        ChatRequest request = new ChatRequest();
        request.setModel(model);
        request.setTemperature(temperature);
        request.setMessages(messages);

        log.debug("OpenAiLlmUtil sending request to model={}", model);

        try {
            ChatResponse response = restClient.post()
                    .uri("/chat/completions") // 标准 OpenAI 接口路径
                    .body(request)
                    .retrieve()
                    .body(ChatResponse.class);

            if (response == null || response.getChoices() == null || response.getChoices().isEmpty()) {
                throw new RuntimeException("LLM 返回为空");
            }
            return response.getChoices().get(0).getMessage().getContent();
        } catch (Exception e) {
            log.error("OpenAiLlmUtil request failed: {}", e.getMessage());
            throw new RuntimeException("OpenAI LLM 调用失败: " + e.getMessage(), e);
        }
    }

    // ===== DTOs =====

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
