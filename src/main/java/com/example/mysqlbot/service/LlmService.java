package com.example.mysqlbot.service;

import com.example.mysqlbot.config.AppConfig;
import com.example.mysqlbot.model.LlmConfig;
import com.example.mysqlbot.repository.LlmConfigRepository;
import com.example.mysqlbot.service.llm.LlmProvider;
import com.example.mysqlbot.service.llm.OpenAiCompatibleProvider;
import com.example.mysqlbot.service.llm.ZhipuProvider;
import com.example.mysqlbot.util.OpenAiLlmUtil;
import com.example.mysqlbot.util.OpenAiLlmUtil.ChatResult;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LLM service with multi-provider support and provider caching by LlmConfig ID.
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
        AppConfig.LlmConfig llm = appConfig.getLlm();
        if (llm.getApiKey() != null && !llm.getApiKey().isBlank() && !"your-api-key".equals(llm.getApiKey())) {
            providerCache.computeIfAbsent(GLOBAL_CONFIG_KEY, k -> createProviderFromAppConfig());
        } else {
            log.warn("LlmService: global API Key not configured, skipping pre-warm");
        }
    }

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
        if (config == null) return chatWithSystem(systemPrompt, userMessage, temperature);
        LlmProvider provider = providerCache.computeIfAbsent(config.getId(), k -> createProvider(config));
        String modelName = resolveModelName(config);
        double temp = (temperature != null) ? temperature : config.getTemperature().doubleValue();
        return provider.chat(systemPrompt, userMessage, temp, modelName);
    }

    public String chatWithMessages(List<Map<String, String>> messages, Double temperature, LlmConfig config) {
        LlmProvider provider;
        String modelName;
        double temp;

        if (config != null) {
            provider = providerCache.computeIfAbsent(config.getId(), k -> createProvider(config));
            modelName = resolveModelName(config);
            temp = (temperature != null) ? temperature : config.getTemperature().doubleValue();
        } else {
            provider = providerCache.computeIfAbsent(GLOBAL_CONFIG_KEY, k -> createProviderFromAppConfig());
            AppConfig.LlmConfig llm = appConfig.getLlm();
            modelName = llm.getModelMap().getOrDefault(llm.getDefaultModel(), llm.getDefaultModel());
            temp = (temperature != null) ? temperature : llm.getTemperature();
        }

        return provider.chatWithMessages(messages, temp, modelName);
    }

    /**
     * Streaming chat: delivers tokens in real-time via callback.
     * Returns the full accumulated response text.
     */
    public String chatStream(String systemPrompt, String userMessage, Double temperature,
                             LlmConfig config, OpenAiLlmUtil.StreamCallback callback) {
        return chatStream(systemPrompt, userMessage, temperature, config, false, callback);
    }

    /**
     * Streaming chat with optional deep-thinking mode.
     * When {@code thinking} is true the model is overridden to the configured reasoning model
     * (e.g. deepseek-reasoner) so the provider streams reasoning_content tokens.
     */
    public String chatStream(String systemPrompt, String userMessage, Double temperature,
                             LlmConfig config, boolean thinking, OpenAiLlmUtil.StreamCallback callback) {
        java.util.List<java.util.Map<String, String>> messages = new java.util.ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(java.util.Map.of("role", "system", "content", systemPrompt));
        }
        messages.add(java.util.Map.of("role", "user", "content", userMessage));
        return chatStreamWithMessages(messages, temperature, config, thinking, callback);
    }

    /**
     * Streaming multi-turn chat using a pre-built messages array.
     * The caller is responsible for building the full system/user/assistant sequence.
     * Supports deep-thinking mode (thinking=true switches to the reasoning model).
     */
    public String chatStreamWithMessages(List<Map<String, String>> messages, Double temperature,
                                         LlmConfig config, boolean thinking,
                                         OpenAiLlmUtil.StreamCallback callback) {
        LlmProvider provider;
        String modelName;
        double temp;

        if (config != null) {
            provider = providerCache.computeIfAbsent(config.getId(), k -> createProvider(config));
            modelName = thinking ? resolveReasoningModel(config) : resolveModelName(config);
            temp = (temperature != null) ? temperature : config.getTemperature().doubleValue();
        } else {
            provider = providerCache.computeIfAbsent(GLOBAL_CONFIG_KEY, k -> createProviderFromAppConfig());
            AppConfig.LlmConfig llm = appConfig.getLlm();
            modelName = thinking ? appConfig.getLlm().getReasoningModel()
                    : llm.getModelMap().getOrDefault(llm.getDefaultModel(), llm.getDefaultModel());
            temp = (temperature != null) ? temperature : llm.getTemperature();
        }

        log.debug("LlmService.chatStreamWithMessages: thinking={}, model={}, messages={}", thinking, modelName, messages.size());
        return provider.chatStreamWithMessages(messages, temp, modelName, callback);
    }

    public String getModelName() {
        AppConfig.LlmConfig llm = appConfig.getLlm();
        return llm.getModelMap().getOrDefault(llm.getDefaultModel(), llm.getDefaultModel());
    }

    public void evictProvider(Long configId) {
        LlmProvider old = providerCache.remove(configId);
        if (old != null) {
            old.close();
            log.info("LlmService: evicted provider cache, configId={}", configId);
        }
    }

    public void evictGlobalProvider() {
        evictProvider(GLOBAL_CONFIG_KEY);
    }

    private LlmProvider createProvider(LlmConfig config) {
        String apiKey = config.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new RuntimeException("LLM config [" + config.getName() + "] API Key not configured");
        }
        String baseUrl = config.getBaseUrl();
        String modelName = resolveModelName(config);

        if (isZhipuUrl(baseUrl)) {
            log.info("LlmService: creating ZhipuProvider, configId={}, name={}", config.getId(), config.getName());
            return new ZhipuProvider(apiKey);
        } else {
            log.info("LlmService: creating OpenAiCompatibleProvider, configId={}, baseUrl={}", config.getId(), baseUrl);
            return new OpenAiCompatibleProvider(baseUrl, apiKey, modelName);
        }
    }

    private LlmProvider createProviderFromAppConfig() {
        AppConfig.LlmConfig llm = appConfig.getLlm();
        String apiKey = llm.getApiKey();
        String baseUrl = llm.getBaseUrl();
        String modelName = llm.getModelMap().getOrDefault(llm.getDefaultModel(), llm.getDefaultModel());

        if (isZhipuUrl(baseUrl)) {
            log.info("LlmService: creating global ZhipuProvider");
            return new ZhipuProvider(apiKey);
        } else {
            log.info("LlmService: creating global OpenAiCompatibleProvider, baseUrl={}", baseUrl);
            return new OpenAiCompatibleProvider(baseUrl, apiKey, modelName);
        }
    }

    private String resolveModelName(LlmConfig config) {
        String alias = config.getDefaultModel();
        Map<String, String> map = config.getModelMap();
        return (map != null && map.containsKey(alias)) ? map.get(alias) : alias;
    }

    /**
     * Resolve the reasoning (deep-thinking) model name for a DB-backed config.
     * Prefers a "reasoning" alias declared in the config's modelMap, otherwise falls back
     * to the globally configured reasoning model (mysqlbot.llm.reasoning-model).
     */
    private String resolveReasoningModel(LlmConfig config) {
        Map<String, String> map = config.getModelMap();
        if (map != null) {
            for (Map.Entry<String, String> e : map.entrySet()) {
                if (e.getKey() != null && e.getKey().toLowerCase().contains("reason")) {
                    return e.getValue();
                }
            }
        }
        return appConfig.getLlm().getReasoningModel();
    }

    /**
     * Chat with tools/function calling support.
     * Returns a structured result containing content and tool calls.
     */
    public ChatResult chatWithMessagesAndTools(
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            Double temperature,
            LlmConfig config) {
        if (config != null) {
            LlmProvider provider = providerCache.computeIfAbsent(config.getId(), k -> createProvider(config));
            String modelName = resolveModelName(config);
            double temp = (temperature != null) ? temperature : config.getTemperature().doubleValue();
            return provider.chatWithMessagesAndTools(messages, tools, temp, modelName);
        } else {
            LlmProvider provider = providerCache.computeIfAbsent(GLOBAL_CONFIG_KEY, k -> createProviderFromAppConfig());
            AppConfig.LlmConfig llm = appConfig.getLlm();
            String modelName = llm.getModelMap().getOrDefault(llm.getDefaultModel(), llm.getDefaultModel());
            double temp = (temperature != null) ? temperature : llm.getTemperature();
            return provider.chatWithMessagesAndTools(messages, tools, temp, modelName);
        }
    }

    private boolean isZhipuUrl(String baseUrl) {
        return baseUrl != null && baseUrl.contains("bigmodel.cn");
    }
}
