package com.example.mysqlbot.service.llm;

import ai.z.openapi.ZhipuAiClient;
import ai.z.openapi.service.model.ChatCompletionCreateParams;
import ai.z.openapi.service.model.ChatCompletionResponse;
import ai.z.openapi.service.model.ChatMessage;
import ai.z.openapi.service.model.ChatMessageRole;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

@Slf4j
public class ZhipuProvider implements LlmProvider {

    private final ZhipuAiClient client;

    public ZhipuProvider(String apiKey) {
        this.client = ZhipuAiClient.builder()
                .ofZHIPU()
                .apiKey(apiKey)
                .build();
        log.info("ZhipuProvider: 客户端已初始化");
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
                String errorMsg = (response != null && response.getMsg() != null) ? response.getMsg() : "未知错误";
                throw new RuntimeException("智谱 API 调用失败: " + errorMsg);
            }

            var choices = response.getData().getChoices();
            if (choices == null || choices.isEmpty()) {
                throw new RuntimeException("智谱 API 返回内容为空");
            }

            Object contentObj = choices.get(0).getMessage().getContent();
            return contentObj != null ? contentObj.toString() : null;
        } catch (Exception e) {
            log.error("ZhipuProvider chat 调用异常", e);
            throw new RuntimeException("智谱 LLM 调用失败: " + e.getMessage(), e);
        }
    }
}
