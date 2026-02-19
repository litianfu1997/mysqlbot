package com.example.mysqlbot.service;

import ai.z.openapi.ZhipuAiClient;
import ai.z.openapi.service.model.ChatCompletionCreateParams;
import ai.z.openapi.service.model.ChatCompletionResponse;
import ai.z.openapi.service.model.ChatMessage;
import ai.z.openapi.service.model.ChatMessageRole;
import com.example.mysqlbot.config.AppConfig;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 智谱 LLM 对话服务（zai-sdk）
 * 统一封装 Chat Completion 调用，替代 Spring AI ChatClient 和自定义 HTTP 客户端
 * <p>
 * 调用链: client.chat().createChatCompletion(ChatCompletionCreateParams)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ZhipuLlmService {

    private final AppConfig appConfig;

    private volatile ZhipuAiClient zhipuClient;
    private volatile boolean isZhipu = true;

    @PostConstruct
    public void init() {
        buildClient();
    }

    private synchronized void buildClient() {
        AppConfig.LlmConfig llm = appConfig.getLlm();
        String apiKey = llm.getApiKey();
        String baseUrl = llm.getBaseUrl();
        String model = llm.getDefaultModel(); // 别名

        // 映射真实模型名
        if (llm.getModelMap().containsKey(model)) {
            model = llm.getModelMap().get(model);
        }

        if (apiKey == null || apiKey.isBlank() || "your-api-key".equals(apiKey)) {
            log.warn("LlmService: API Key 未配置");
            this.zhipuClient = null;
            return;
        }

        // 策略路由：智谱 SDK vs 通用 OpenAI
        // 判定条件：baseUrl 包含 "bigmodel.cn" 或者模型名含 "glm"
        if ((baseUrl != null && baseUrl.contains("bigmodel.cn"))
                || (model != null && model.toLowerCase().contains("glm"))) {
            isZhipu = true;
            this.zhipuClient = ZhipuAiClient.builder().ofZHIPU()
                    .apiKey(apiKey)
                    .build();
            log.info("LlmService: 初始化智谱 SDK (ZAI-SDK), model={}", model);
        } else {
            isZhipu = false;
            // 确保 baseUrl 只要是有值的就行，空则默认 OpenAI
            String targetBaseUrl = (baseUrl != null && !baseUrl.isBlank()) ? baseUrl : "https://api.openai.com/v1";
            log.info("LlmService: 初始化通用 OpenAI 客户端, baseUrl={}, model={}", targetBaseUrl, model);
        }
    }

    /**
     * 发送单轮 Chat 请求，返回 assistant 回复文本
     */
    public String chat(String userMessage, Double temperature) {
        return chatWithSystem(null, userMessage, temperature);
    }

    /**
     * 发送单轮 Chat 请求，返回 assistant 回复文本
     */
    public String chatWithSystem(String systemPrompt, String userMessage, Double temperature) {
        ensureClient();

        AppConfig.LlmConfig llm = appConfig.getLlm();
        String modelName = llm.getModelMap().getOrDefault(llm.getDefaultModel(), llm.getDefaultModel());
        double temp = (temperature != null) ? temperature : llm.getTemperature();

        if (isZhipu) {
            return callZhipuSdk(systemPrompt, userMessage, temp, modelName);
        } else {
            return callOpenAiGeneric(systemPrompt, userMessage, temp, modelName);
        }
    }

    // ===== 内部私有方法 =====

    private String callZhipuSdk(String systemPrompt, String userMessage, double temperature, String modelName) {
        // 构建消息列表
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

            log.debug("Zhipu(SDK) chat: model={}, temperature={}", modelName, temperature);
            ChatCompletionResponse response = zhipuClient.chat().createChatCompletion(params);

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
            log.error("Zhipu SDK 调用异常", e);
            throw new RuntimeException("智谱 LLM 调用失败: " + e.getMessage(), e);
        }
    }

    private String callOpenAiGeneric(String systemPrompt, String userMessage, double temperature, String modelName) {
        String baseUrl = appConfig.getLlm().getBaseUrl();
        String apiKey = appConfig.getLlm().getApiKey();

        // 兼容处理：如果没有配置 baseUrl，给一个默认
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://api.openai.com/v1";
        }

        // 每次调用都实例化 Util (轻量级 RestClient)，或者可以缓存 Util 实例
        // 这里简单起见直接 new，RestClient 开销较小
        com.example.mysqlbot.util.OpenAiLlmUtil util = new com.example.mysqlbot.util.OpenAiLlmUtil(
                baseUrl, apiKey, modelName);

        List<java.util.Map<String, String>> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(java.util.Map.of("role", "system", "content", systemPrompt));
        }
        messages.add(java.util.Map.of("role", "user", "content", userMessage));

        log.debug("OpenAI(Generic) chat: url={}, model={}", baseUrl, modelName);
        return util.chat(messages, temperature);
    }

    /** 获取当前使用的模型名称 */
    public String getModelName() {
        AppConfig.LlmConfig llm = appConfig.getLlm();
        return llm.getModelMap().getOrDefault(llm.getDefaultModel(), llm.getDefaultModel());
    }

    private void ensureClient() {
        // 如果是首次调用或配置变更，重新构建
        if (zhipuClient == null && isZhipu) {
            buildClient();
        } else if (!isZhipu) {
            // 通用模式下，确保配置了 API Key
            String apiKey = appConfig.getLlm().getApiKey();
            if (apiKey == null || apiKey.isBlank()) {
                buildClient(); // 尝试重新加载
            }
        }

        // 校验
        if (isZhipu && zhipuClient == null) {
            throw new RuntimeException("ZhipuLlmService (智谱模式): API Key 未配置或初始化失败");
        }
        if (!isZhipu) {
            String apiKey = appConfig.getLlm().getApiKey();
            if (apiKey == null || apiKey.isBlank()) {
                throw new RuntimeException("ZhipuLlmService (OpenAI模式): API Key 未配置");
            }
        }
    }
}
