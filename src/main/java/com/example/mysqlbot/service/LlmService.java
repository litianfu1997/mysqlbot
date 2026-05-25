package com.example.mysqlbot.service;

import com.example.mysqlbot.config.AppConfig;
import com.example.mysqlbot.model.LlmConfig;
import com.example.mysqlbot.repository.LlmConfigRepository;
import com.example.mysqlbot.service.llm.LlmProvider;
import com.example.mysqlbot.service.llm.OpenAiCompatibleProvider;
import com.example.mysqlbot.service.llm.ZhipuProvider;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LLM 对话服务，统一封装多厂商调用（智谱/DeepSeek/OpenAI 兼容协议）。
 * Provider 实例按 LlmConfig ID 缓存，避免每次调用重建客户端。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmService {

    private static final Long GLOBAL_CONFIG_KEY = -1L;

    private final AppConfig appConfig;
    private final LlmConfigRepository llmConfigRepository;

    private final ConcurrentHashMap<Long, LlmProvider> providerCache = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        // 预热全局配置对应的 Provider
        AppConfig.LlmConfig llm = appConfig.getLlm();
        if (llm.getApiKey() != null && !llm.getApiKey().isBlank() && !"your-api-key".equals(llm.getApiKey())) {
            providerCache.computeIfAbsent(GLOBAL_CONFIG_KEY, k -> createProviderFromAppConfig());
        } else {
            log.warn("LlmService: 全局 API Key 未配置，跳过预热");
        }
    }

    // ===== 对外接口（与 ZhipuLlmService 签名完全一致） =====

    public String chat(String userMessage, Double temperature) {
        return chatWithSystem(null, userMessage, temperature);
    }

    public String chatWithSystem(String systemPrompt, String userMessage, Double temperature) {
        LlmProvider provider = providerCache.computeIfAbsent(GLOBAL_CONFIG_KEY, k -> createProviderFromAppConfig());
        AppConfig.LlmConfig llm = appConfig.getLlm();
        String modelName = llm.getModelMap().getOrDefault(llm.getDefaultModel(), llm.getDefaultModel());
        double temp = (temperature != null) ? temperature : llm.getTemperature();
        return provider.chat(systemPrompt, userMessage, temp, modelName);
    }

    public String chatWithConfig(String systemPrompt, String userMessage, Double temperature, LlmConfig config) {
        if (config == null) {
            return chatWithSystem(systemPrompt, userMessage, temperature);
        }
        LlmProvider provider = providerCache.computeIfAbsent(config.getId(), k -> createProvider(config));
        String modelName = resolveModelName(config);
        double temp = (temperature != null) ? temperature : config.getTemperature().doubleValue();
        return provider.chat(systemPrompt, userMessage, temp, modelName);
    }

    public String getModelName() {
        AppConfig.LlmConfig llm = appConfig.getLlm();
        return llm.getModelMap().getOrDefault(llm.getDefaultModel(), llm.getDefaultModel());
    }

    // ===== 缓存管理 =====

    public void evictProvider(Long configId) {
        LlmProvider old = providerCache.remove(configId);
        if (old != null) {
            old.close();
            log.info("LlmService: 已驱逐 Provider 缓存, configId={}", configId);
        }
    }

    public void evictGlobalProvider() {
        evictProvider(GLOBAL_CONFIG_KEY);
    }

    // ===== 私有工厂方法 =====

    private LlmProvider createProvider(LlmConfig config) {
        String apiKey = config.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new RuntimeException("LLM配置 [" + config.getName() + "] API Key 未配置");
        }
        String baseUrl = config.getBaseUrl();
        String modelName = resolveModelName(config);

        if (isZhipuUrl(baseUrl)) {
            log.info("LlmService: 创建 ZhipuProvider, configId={}, name={}", config.getId(), config.getName());
            return new ZhipuProvider(apiKey);
        } else {
            log.info("LlmService: 创建 OpenAiCompatibleProvider, configId={}, baseUrl={}", config.getId(), baseUrl);
            return new OpenAiCompatibleProvider(baseUrl, apiKey, modelName);
        }
    }

    private LlmProvider createProviderFromAppConfig() {
        AppConfig.LlmConfig llm = appConfig.getLlm();
        String apiKey = llm.getApiKey();
        String baseUrl = llm.getBaseUrl();
        String modelName = llm.getModelMap().getOrDefault(llm.getDefaultModel(), llm.getDefaultModel());

        if (isZhipuUrl(baseUrl)) {
            log.info("LlmService: 创建全局 ZhipuProvider");
            return new ZhipuProvider(apiKey);
        } else {
            log.info("LlmService: 创建全局 OpenAiCompatibleProvider, baseUrl={}", baseUrl);
            return new OpenAiCompatibleProvider(baseUrl, apiKey, modelName);
        }
    }

    private String resolveModelName(LlmConfig config) {
        String alias = config.getDefaultModel();
        Map<String, String> map = config.getModelMap();
        return (map != null && map.containsKey(alias)) ? map.get(alias) : alias;
    }

    private boolean isZhipuUrl(String baseUrl) {
        return baseUrl != null && baseUrl.contains("bigmodel.cn");
    }
}
