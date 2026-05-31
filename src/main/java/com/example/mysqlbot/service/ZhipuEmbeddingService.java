package com.example.mysqlbot.service;

import ai.z.openapi.ZhipuAiClient;
import ai.z.openapi.service.embedding.EmbeddingCreateParams;
import ai.z.openapi.service.embedding.EmbeddingResponse;
import com.example.mysqlbot.config.AppConfig;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Embedding service with result caching.
 * Uses zai-sdk to call embedding API, caches results to avoid duplicate calls.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ZhipuEmbeddingService {

    private final AppConfig appConfig;

    private static final int DIMENSIONS = 1024;
    private static final int CACHE_MAX_SIZE = 500;

    private volatile ZhipuAiClient client;

    // Simple LRU cache for embedding results
    private final Map<String, float[]> embeddingCache = new LinkedHashMap<String, float[]>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, float[]> eldest) {
            return size() > CACHE_MAX_SIZE;
        }
    };

    @PostConstruct
    public void init() {
        buildClient();
    }

    public synchronized void buildClient() {
        String apiKey = appConfig.getLlm().getApiKey();
        String baseUrl = appConfig.getLlm().getBaseUrl();
        if (apiKey == null || apiKey.isBlank() || "your-api-key".equals(apiKey)) {
            log.warn("ZhipuEmbeddingService: API Key not configured, will initialize on use");
            this.client = null;
            return;
        }
        // Only use ZhipuAiClient when base URL is Zhipu
        boolean isZhipu = baseUrl != null && baseUrl.contains("bigmodel.cn");
        if (!isZhipu) {
            log.info("ZhipuEmbeddingService: skipping Zhipu client init (non-Zhipu base URL: {})", baseUrl);
            this.client = null;
            return;
        }
        this.client = ZhipuAiClient.builder().ofZHIPU()
                .apiKey(apiKey)
                .build();
        log.info("ZhipuEmbeddingService: client initialized");
    }

    /**
     * Embed a single text with caching.
     */
    public float[] embed(String text) {
        // Check cache first
        synchronized (embeddingCache) {
            float[] cached = embeddingCache.get(text);
            if (cached != null) {
                log.debug("Embedding cache hit for text ({} chars)", text.length());
                return cached;
            }
        }

        float[] result = embedBatch(List.of(text)).get(0);

        // Store in cache
        synchronized (embeddingCache) {
            embeddingCache.put(text, result);
        }

        return result;
    }

    /**
     * Batch embed with caching. Checks cache per-item first.
     */
    public List<float[]> embedBatch(List<String> texts) {
        ensureClient();

        // Check which texts need embedding
        java.util.List<Integer> uncachedIndices = new java.util.ArrayList<>();
        java.util.List<String> uncachedTexts = new java.util.ArrayList<>();
        java.util.Map<Integer, float[]> cachedResults = new java.util.LinkedHashMap<>();

        synchronized (embeddingCache) {
            for (int i = 0; i < texts.size(); i++) {
                float[] cached = embeddingCache.get(texts.get(i));
                if (cached != null) {
                    cachedResults.put(i, cached);
                } else {
                    uncachedIndices.add(i);
                    uncachedTexts.add(texts.get(i));
                }
            }
        }

        // Fetch uncached embeddings in batch
        if (!uncachedTexts.isEmpty()) {
            List<float[]> newEmbeddings = doEmbedBatch(uncachedTexts);
            synchronized (embeddingCache) {
                for (int j = 0; j < uncachedTexts.size(); j++) {
                    int origIdx = uncachedIndices.get(j);
                    float[] emb = newEmbeddings.get(j);
                    cachedResults.put(origIdx, emb);
                    embeddingCache.put(uncachedTexts.get(j), emb);
                }
            }
        }

        // Build ordered result list
        java.util.List<float[]> finalResults = new java.util.ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            finalResults.add(cachedResults.get(i));
        }
        return finalResults;
    }

    private List<float[]> doEmbedBatch(List<String> texts) {
        final int BATCH_SIZE = 64;
        if (texts.size() <= BATCH_SIZE) {
            return doEmbed(texts);
        }

        List<float[]> all = new java.util.ArrayList<>();
        for (int i = 0; i < texts.size(); i += BATCH_SIZE) {
            List<String> batch = texts.subList(i, Math.min(i + BATCH_SIZE, texts.size()));
            all.addAll(doEmbed(batch));
        }
        return all;
    }

    private List<float[]> doEmbed(List<String> texts) {
        EmbeddingCreateParams params = EmbeddingCreateParams.builder()
                .model("embedding-3")
                .input(texts)
                .dimensions(DIMENSIONS)
                .build();

        EmbeddingResponse response = client.embeddings().createEmbeddings(params);

        if (response == null || response.getData() == null
                || response.getData().getData() == null
                || response.getData().getData().isEmpty()) {
            throw new RuntimeException("Embedding API returned empty result");
        }

        return response.getData().getData().stream()
                .sorted((a, b) -> Integer.compare(a.getIndex(), b.getIndex()))
                .map(item -> {
                    List<Double> vec = item.getEmbedding();
                    float[] arr = new float[vec.size()];
                    for (int i = 0; i < vec.size(); i++) {
                        arr[i] = vec.get(i).floatValue();
                    }
                    return arr;
                })
                .collect(Collectors.toList());
    }

    public int getDimensions() {
        return DIMENSIONS;
    }

    /** Clear the embedding cache (e.g. when switching configs). */
    public void clearCache() {
        synchronized (embeddingCache) {
            embeddingCache.clear();
        }
        log.info("Embedding cache cleared");
    }

    private void ensureClient() {
        if (client == null) buildClient();
        if (client == null) throw new RuntimeException("Embedding service not available (requires Zhipu API key with bigmodel.cn base URL)");
    }
}
