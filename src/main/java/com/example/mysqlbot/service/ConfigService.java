package com.example.mysqlbot.service;

import com.example.mysqlbot.config.AppConfig;
import com.example.mysqlbot.model.SystemConfig;
import com.example.mysqlbot.repository.SystemConfigRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 配置管理服务
 * 允许在运行时动态获取和更新系统配置，且持久化到数据库
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigService {

    private static final String KEY_LLM_BASE_URL = "llm.base_url";
    private static final String KEY_LLM_API_KEY = "llm.api_key";
    private static final String KEY_LLM_DEFAULT_MODEL = "llm.default_model";
    private static final String KEY_LLM_TEMPERATURE = "llm.temperature";
    private static final String KEY_LLM_MODEL_MAP = "llm.model_map";

    private final AppConfig appConfig;
    private final SystemConfigRepository configRepository;
    private final ObjectMapper objectMapper;

    /**
     * 启动时从数据库加载配置，覆盖 application.yml 的默认值
     */
    @PostConstruct
    public void loadConfigFromDb() {
        try {
            AppConfig.LlmConfig llm = appConfig.getLlm();

            configRepository.findById(KEY_LLM_BASE_URL).ifPresent(c -> llm.setBaseUrl(c.getConfigValue()));
            configRepository.findById(KEY_LLM_API_KEY).ifPresent(c -> llm.setApiKey(c.getConfigValue()));
            configRepository.findById(KEY_LLM_DEFAULT_MODEL).ifPresent(c -> llm.setDefaultModel(c.getConfigValue()));
            configRepository.findById(KEY_LLM_TEMPERATURE).ifPresent(c -> {
                try {
                    llm.setTemperature(Double.parseDouble(c.getConfigValue()));
                } catch (NumberFormatException ignored) {
                }
            });
            configRepository.findById(KEY_LLM_MODEL_MAP).ifPresent(c -> {
                try {
                    Map<String, String> map = objectMapper.readValue(c.getConfigValue(), new TypeReference<>() {
                    });
                    llm.setModelMap(new java.util.HashMap<>(map));
                } catch (Exception e) {
                    log.warn("Failed to parse llm.model_map from db", e);
                }
            });

            log.info("Loaded LLM config from DB: baseUrl={}, model={}", llm.getBaseUrl(), llm.getDefaultModel());
        } catch (Exception e) {
            log.warn("Failed to load config from DB (table may not exist yet): {}", e.getMessage());
        }
    }

    /**
     * 获取 LLM 配置
     */
    public AppConfig.LlmConfig getLlmConfig() {
        return appConfig.getLlm();
    }

    /**
     * 更新 LLM 配置并持久化到数据库
     */
    public void updateLlmConfig(AppConfig.LlmConfig newConfig) {
        AppConfig.LlmConfig current = appConfig.getLlm();

        if (newConfig.getBaseUrl() != null) {
            current.setBaseUrl(newConfig.getBaseUrl());
            saveConfig(KEY_LLM_BASE_URL, newConfig.getBaseUrl(), "LLM API Base URL");
        }
        if (newConfig.getApiKey() != null) {
            current.setApiKey(newConfig.getApiKey());
            saveConfig(KEY_LLM_API_KEY, newConfig.getApiKey(), "LLM API Key");
        }
        if (newConfig.getDefaultModel() != null) {
            current.setDefaultModel(newConfig.getDefaultModel());
            saveConfig(KEY_LLM_DEFAULT_MODEL, newConfig.getDefaultModel(), "LLM 默认模型");
        }
        if (newConfig.getModelMap() != null) {
            current.setModelMap(new java.util.HashMap<>(newConfig.getModelMap()));
            try {
                saveConfig(KEY_LLM_MODEL_MAP, objectMapper.writeValueAsString(newConfig.getModelMap()), "LLM 模型映射表");
            } catch (Exception e) {
                log.error("Failed to serialize modelMap", e);
            }
        }
        if (newConfig.getTemperature() > 0) {
            current.setTemperature(newConfig.getTemperature());
            saveConfig(KEY_LLM_TEMPERATURE, String.valueOf(newConfig.getTemperature()), "LLM Temperature");
        }
        log.info("LLM 配置已更新并持久化: {}", current);
    }

    /**
     * 更新 LLM 模型（仅切换别名，不修改映射）
     */
    public void updateModel(String modelAlias) {
        Map<String, String> map = appConfig.getLlm().getModelMap();
        if (map.containsKey(modelAlias)) {
            appConfig.getLlm().setDefaultModel(modelAlias);
            saveConfig(KEY_LLM_DEFAULT_MODEL, modelAlias, "LLM 默认模型");
            log.info("LLM 模型切换为: {}", modelAlias);
        } else {
            throw new IllegalArgumentException("不支持的模型: " + modelAlias);
        }
    }

    /**
     * 获取 SQL 执行配置
     */
    public AppConfig.SqlConfig getSqlConfig() {
        return appConfig.getSql();
    }

    /**
     * 测试 LLM 连接（使用智谱 zai-sdk）
     */
    public boolean testLlmConnection(AppConfig.LlmConfig config) {
        try {
            AppConfig.LlmConfig llm = appConfig.getLlm();

            String apiKey = config.getApiKey() != null ? config.getApiKey() : llm.getApiKey();
            String modelAlias = config.getDefaultModel() != null ? config.getDefaultModel() : llm.getDefaultModel();

            // 解析别名 -> 实际模型名
            String modelName = modelAlias;
            if (config.getModelMap() != null && config.getModelMap().containsKey(modelAlias)) {
                modelName = config.getModelMap().get(modelAlias);
            } else if (llm.getModelMap().containsKey(modelAlias)) {
                modelName = llm.getModelMap().get(modelAlias);
            }

            log.info("Testing LLM connection via ZhipuAiClient: model={}", modelName);

            // 使用 zai-sdk 测试连接
            ai.z.openapi.ZhipuAiClient zhipuClient = ai.z.openapi.ZhipuAiClient.builder()
                    .ofZHIPU()
                    .apiKey(apiKey)
                    .build();

            var messages = java.util.List.of(
                    ai.z.openapi.service.model.ChatMessage.builder()
                            .role(ai.z.openapi.service.model.ChatMessageRole.USER.value())
                            .content("请用一句话回答：1+1等于多少？")
                            .build());

            ai.z.openapi.service.model.ChatCompletionCreateParams params = ai.z.openapi.service.model.ChatCompletionCreateParams
                    .builder()
                    .model(modelName)
                    .messages(messages)
                    .temperature(0.1f) // 显式使用 float
                    .build();

            ai.z.openapi.service.model.ChatCompletionResponse response = zhipuClient.chat()
                    .createChatCompletion(params);

            boolean success = response != null
                    && response.isSuccess()
                    && response.getData() != null
                    && response.getData().getChoices() != null
                    && !response.getData().getChoices().isEmpty();
            log.info("LLM connection test {}", success ? "success" : "failed");
            return success;
        } catch (Exception e) {
            log.error("LLM connection test failed", e);
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    // ===== private helpers =====

    /**
     * 去掉 base URL 末尾的 /v1（Spring AI OpenAiApi 内部会自动拼接 /v1）
     */
    public static String stripV1Suffix(String baseUrl) {
        if (baseUrl == null)
            return null;
        String url = baseUrl.trim();
        if (url.endsWith("/v1")) {
            url = url.substring(0, url.length() - 3);
        } else if (url.endsWith("/v1/")) {
            url = url.substring(0, url.length() - 4);
        }
        return url;
    }

    private void saveConfig(String key, String value, String description) {
        SystemConfig cfg = configRepository.findById(key).orElse(new SystemConfig());
        cfg.setConfigKey(key);
        cfg.setConfigValue(value);
        cfg.setDescription(description);
        configRepository.save(cfg);
    }
}
