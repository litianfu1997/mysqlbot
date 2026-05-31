package com.example.mysqlbot.service.llm;

import ai.z.openapi.ZhipuAiClient;
import ai.z.openapi.service.model.ChatCompletionCreateParams;
import ai.z.openapi.service.model.ChatCompletionResponse;
import ai.z.openapi.service.model.ChatMessage;
import ai.z.openapi.service.model.ChatMessageRole;
import ai.z.openapi.service.model.Delta;
import ai.z.openapi.service.model.ModelData;
import com.example.mysqlbot.util.OpenAiLlmUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
public class ZhipuProvider implements LlmProvider {

    private final ZhipuAiClient client;

    public ZhipuProvider(String apiKey) {
        this.client = ZhipuAiClient.builder()
                .ofZHIPU()
                .apiKey(apiKey)
                .build();
        log.info("ZhipuProvider: client initialized");
    }

    @Override
    public String chat(String systemPrompt, String userMessage, double temperature, String modelName) {
        List<ChatMessage> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(ChatMessage.builder()
                    .role(ChatMessageRole.SYSTEM.value())
                    .content(systemPrompt)
                    .build());
        }
        messages.add(ChatMessage.builder()
                .role(ChatMessageRole.USER.value())
                .content(userMessage)
                .build());

        try {
            ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                    .model(modelName)
                    .messages(messages)
                    .temperature((float) temperature)
                    .build();

            log.debug("ZhipuProvider chat: model={}, temperature={}", modelName, temperature);
            ChatCompletionResponse response = client.chat().createChatCompletion(params);

            if (response == null || !response.isSuccess() || response.getData() == null) {
                String errorMsg = (response != null && response.getMsg() != null) ? response.getMsg() : "Unknown error";
                throw new RuntimeException("Zhipu API call failed: " + errorMsg);
            }

            var choices = response.getData().getChoices();
            if (choices == null || choices.isEmpty()) {
                throw new RuntimeException("Zhipu API returned empty choices");
            }

            Object contentObj = choices.get(0).getMessage().getContent();
            return contentObj != null ? contentObj.toString() : null;
        } catch (Exception e) {
            log.error("ZhipuProvider chat failed", e);
            throw new RuntimeException("Zhipu LLM call failed: " + e.getMessage(), e);
        }
    }

    @Override
    public String chatWithMessages(List<Map<String, String>> messages, double temperature, String modelName) {
        List<ChatMessage> zhipuMessages = new ArrayList<>();
        for (Map<String, String> message : messages) {
            zhipuMessages.add(ChatMessage.builder()
                    .role(message.getOrDefault("role", ChatMessageRole.USER.value()))
                    .content(message.getOrDefault("content", ""))
                    .build());
        }

        try {
            ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                    .model(modelName)
                    .messages(zhipuMessages)
                    .temperature((float) temperature)
                    .build();

            log.debug("ZhipuProvider chatWithMessages: model={}, messages={}", modelName, zhipuMessages.size());
            ChatCompletionResponse response = client.chat().createChatCompletion(params);

            if (response == null || !response.isSuccess() || response.getData() == null) {
                String errorMsg = (response != null && response.getMsg() != null) ? response.getMsg() : "Unknown error";
                throw new RuntimeException("Zhipu API call failed: " + errorMsg);
            }

            var choices = response.getData().getChoices();
            if (choices == null || choices.isEmpty()) {
                throw new RuntimeException("Zhipu API returned empty choices");
            }

            Object contentObj = choices.get(0).getMessage().getContent();
            return contentObj != null ? contentObj.toString() : null;
        } catch (Exception e) {
            log.error("ZhipuProvider chatWithMessages failed", e);
            throw new RuntimeException("Zhipu LLM call failed: " + e.getMessage(), e);
        }
    }

    @Override
    public String chatStreamWithMessages(List<Map<String, String>> messages, double temperature,
                                         String modelName, OpenAiLlmUtil.StreamCallback callback) {
        List<ChatMessage> zhipuMessages = new ArrayList<>();
        for (Map<String, String> message : messages) {
            zhipuMessages.add(ChatMessage.builder()
                    .role(message.getOrDefault("role", ChatMessageRole.USER.value()))
                    .content(message.getOrDefault("content", ""))
                    .build());
        }

        try {
            ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                    .model(modelName)
                    .messages(zhipuMessages)
                    .temperature((float) temperature)
                    .stream(true)
                    .build();

            log.debug("ZhipuProvider stream chat: model={}, temperature={}", modelName, temperature);
            ChatCompletionResponse response = client.chat().createChatCompletion(params);
            if (response == null || !response.isSuccess() || response.getFlowable() == null) {
                String errorMsg = (response != null && response.getMsg() != null) ? response.getMsg() : "Unknown error";
                throw new RuntimeException("Zhipu streaming API failed: " + errorMsg);
            }

            StringBuilder fullContent = new StringBuilder();
            StringBuilder fullThinking = new StringBuilder();
            response.getFlowable().blockingForEach(data -> appendStreamDelta(data, callback, fullContent, fullThinking));

            if (fullContent.isEmpty() && fullThinking.length() > 0) {
                return fullThinking.toString();
            }
            return fullContent.toString();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("ZhipuProvider stream chat failed", e);
            throw new RuntimeException("Zhipu streaming LLM call failed: " + e.getMessage(), e);
        }
    }

    private void appendStreamDelta(ModelData data, OpenAiLlmUtil.StreamCallback callback,
                                   StringBuilder fullContent, StringBuilder fullThinking) {
        if (data == null || data.getChoices() == null || data.getChoices().isEmpty()) {
            return;
        }

        Delta delta = data.getChoices().get(0).getDelta();
        if (delta == null) {
            return;
        }

        String reasoning = delta.getReasoningContent();
        if (reasoning != null && !reasoning.isEmpty()) {
            fullThinking.append(reasoning);
            callback.onToken("thinking", reasoning);
        }

        String content = delta.getContent();
        if (content != null && !content.isEmpty()) {
            fullContent.append(content);
            callback.onToken("content", content);
        }
    }
}
