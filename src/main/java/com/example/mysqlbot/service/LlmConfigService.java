package com.example.mysqlbot.service;

import com.example.mysqlbot.config.AppConfig;
import com.example.mysqlbot.model.LlmConfig;
import com.example.mysqlbot.repository.LlmConfigRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * LLM配置管理服务
 * 支持多LLM配置的CRUD操作，以及从system_config迁移现有配置
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmConfigService {

    private final LlmConfigRepository llmConfigRepository;
    private final AppConfig appConfig;
    private final ObjectMapper objectMapper;

    /**
     * 启动时检查是否需要从system_config迁移现有LLM配置
     */
    @PostConstruct
    @Transactional
    public void migrateFromSystemConfig() {
        // 如果llm_config表为空，从AppConfig迁移现有配置
        if (llmConfigRepository.count() == 0) {
            AppConfig.LlmConfig config = appConfig.getLlm();
            if (config.getApiKey() != null && !"your-api-key".equals(config.getApiKey())) {
                LlmConfig defaultConfig = LlmConfig.builder()
                        .name("Default")
                        .baseUrl(config.getBaseUrl())
                        .apiKey(config.getApiKey())
                        .modelMap(config.getModelMap() != null ? config.getModelMap() : Map.of())
                        .defaultModel(config.getDefaultModel())
                        .temperature(BigDecimal.valueOf(config.getTemperature()))
                        .isDefault(true)
                        .isEnabled(true)
                        .build();
                llmConfigRepository.save(defaultConfig);
                log.info("Migrated existing LLM config from system_config to llm_config table");
            }
        }
    }

    /**
     * 获取所有LLM配置
     */
    public List<LlmConfig> getAllConfigs() {
        return llmConfigRepository.findAll();
    }

    /**
     * 获取所有启用的LLM配置
     */
    public List<LlmConfig> getEnabledConfigs() {
        return llmConfigRepository.findByIsEnabledTrue();
    }

    /**
     * 根据ID获取LLM配置
     */
    public Optional<LlmConfig> getConfigById(Long id) {
        return llmConfigRepository.findById(id);
    }

    /**
     * 获取默认LLM配置
     */
    public Optional<LlmConfig> getDefaultConfig() {
        return llmConfigRepository.findByIsDefaultTrue();
    }

    /**
     * 创建新的LLM配置
     */
    @Transactional
    public LlmConfig createConfig(LlmConfig config) {
        // 检查名称是否已存在
        if (llmConfigRepository.existsByName(config.getName())) {
            throw new IllegalArgumentException("配置名称已存在: " + config.getName());
        }

        // 如果设为默认，先清除其他默认标记
        if (Boolean.TRUE.equals(config.getIsDefault())) {
            clearDefaultFlag();
        }

        // 如果是第一个配置，自动设为默认
        if (llmConfigRepository.count() == 0) {
            config.setIsDefault(true);
        }

        return llmConfigRepository.save(config);
    }

    /**
     * 更新LLM配置
     */
    @Transactional
    public LlmConfig updateConfig(Long id, LlmConfig updatedConfig) {
        LlmConfig existing = llmConfigRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("配置不存在: " + id));

        // 检查名称是否与其他配置冲突
        if (!existing.getName().equals(updatedConfig.getName())
                && llmConfigRepository.existsByName(updatedConfig.getName())) {
            throw new IllegalArgumentException("配置名称已存在: " + updatedConfig.getName());
        }

        // 更新字段
        existing.setName(updatedConfig.getName());
        existing.setBaseUrl(updatedConfig.getBaseUrl());
        existing.setApiKey(updatedConfig.getApiKey());
        existing.setModelMap(updatedConfig.getModelMap());
        existing.setDefaultModel(updatedConfig.getDefaultModel());
        existing.setTemperature(updatedConfig.getTemperature());
        existing.setIsEnabled(updatedConfig.getIsEnabled());

        // 如果设为默认，先清除其他默认标记
        if (Boolean.TRUE.equals(updatedConfig.getIsDefault()) && !Boolean.TRUE.equals(existing.getIsDefault())) {
            clearDefaultFlag();
            existing.setIsDefault(true);
        }

        return llmConfigRepository.save(existing);
    }

    /**
     * 设置默认配置
     */
    @Transactional
    public void setDefault(Long id) {
        LlmConfig config = llmConfigRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("配置不存在: " + id));

        clearDefaultFlag();
        config.setIsDefault(true);
        llmConfigRepository.save(config);
    }

    /**
     * 删除LLM配置
     */
    @Transactional
    public void deleteConfig(Long id) {
        LlmConfig config = llmConfigRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("配置不存在: " + id));

        if (Boolean.TRUE.equals(config.getIsDefault())) {
            throw new IllegalArgumentException("不能删除默认配置");
        }

        llmConfigRepository.deleteById(id);
    }

    /**
     * 测试LLM连接
     */
    public boolean testConnection(LlmConfig config) {
        try {
            String apiKey = config.getApiKey();
            String modelAlias = config.getDefaultModel();

            // 解析别名 -> 实际模型名
            String modelName = modelAlias;
            if (config.getModelMap() != null && config.getModelMap().containsKey(modelAlias)) {
                modelName = config.getModelMap().get(modelAlias);
            }

            log.info("Testing LLM connection: name={}, model={}", config.getName(), modelName);

            // 判断是否为智谱
            boolean isZhipu = (config.getBaseUrl() != null && config.getBaseUrl().contains("bigmodel.cn"))
                    || (modelName != null && modelName.toLowerCase().contains("glm"));

            if (isZhipu) {
                return testZhipuConnection(apiKey, modelName);
            } else {
                return testOpenAiConnection(config.getBaseUrl(), apiKey, modelName);
            }
        } catch (Exception e) {
            log.error("LLM connection test failed", e);
            throw new RuntimeException("连接测试失败: " + e.getMessage(), e);
        }
    }

    private boolean testZhipuConnection(String apiKey, String modelName) {
        ai.z.openapi.ZhipuAiClient zhipuClient = ai.z.openapi.ZhipuAiClient.builder()
                .ofZHIPU()
                .apiKey(apiKey)
                .build();

        var messages = java.util.List.of(
                ai.z.openapi.service.model.ChatMessage.builder()
                        .role(ai.z.openapi.service.model.ChatMessageRole.USER.value())
                        .content("1+1=?")
                        .build());

        ai.z.openapi.service.model.ChatCompletionCreateParams params = ai.z.openapi.service.model.ChatCompletionCreateParams
                .builder()
                .model(modelName)
                .messages(messages)
                .temperature(0.1f)
                .build();

        ai.z.openapi.service.model.ChatCompletionResponse response = zhipuClient.chat()
                .createChatCompletion(params);

        boolean success = response != null && response.isSuccess() && response.getData() != null
                && response.getData().getChoices() != null && !response.getData().getChoices().isEmpty();

        log.info("Zhipu LLM connection test: {}", success ? "success" : "failed");
        return success;
    }

    private boolean testOpenAiConnection(String baseUrl, String apiKey, String modelName) {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://api.openai.com/v1";
        }

        com.example.mysqlbot.util.OpenAiLlmUtil util = new com.example.mysqlbot.util.OpenAiLlmUtil(
                baseUrl, apiKey, modelName);

        List<Map<String, String>> messages = java.util.List.of(
                Map.of("role", "user", "content", "1+1=?"));

        log.info("OpenAI LLM connection test: url={}, model={}", baseUrl, modelName);
        String response = util.chat(messages, 0.1);
        return response != null && !response.isBlank();
    }

    /**
     * 清除所有默认标记
     */
    private void clearDefaultFlag() {
        llmConfigRepository.findByIsDefaultTrue().ifPresent(config -> {
            config.setIsDefault(false);
            llmConfigRepository.save(config);
        });
    }
}
