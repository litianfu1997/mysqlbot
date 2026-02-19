package com.example.mysqlbot.service;

import ai.z.openapi.ZhipuAiClient;
import ai.z.openapi.service.embedding.EmbeddingCreateParams;
import ai.z.openapi.service.embedding.EmbeddingResponse;
import com.example.mysqlbot.config.AppConfig;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 智谱 embedding-3 嵌入服务
 * 使用 zai-sdk 调用智谱 embedding-3 API 生成向量
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ZhipuEmbeddingService {

    private final AppConfig appConfig;

    /** embedding-3 默认维度 (2048 最高精度，1024 均衡，512 轻量) */
    private static final int DIMENSIONS = 1024;

    private volatile ZhipuAiClient client;

    @PostConstruct
    public void init() {
        buildClient();
    }

    /** 支持在运行时重新初始化客户端（如 API Key 变更后） */
    public synchronized void buildClient() {
        String apiKey = appConfig.getLlm().getApiKey();
        if (apiKey == null || apiKey.isBlank() || "your-api-key".equals(apiKey)) {
            log.warn("ZhipuEmbeddingService: API Key 未配置，将在使用时再初始化");
            this.client = null;
            return;
        }
        this.client = ZhipuAiClient.builder().ofZHIPU()
                .apiKey(apiKey)
                .build();
        log.info("ZhipuEmbeddingService: 客户端初始化完成");
    }

    /**
     * 对单条文本生成嵌入向量
     *
     * @param text 输入文本
     * @return float[] 向量（维度 = DIMENSIONS）
     */
    public float[] embed(String text) {
        return embedBatch(List.of(text)).get(0);
    }

    /**
     * 批量生成嵌入向量（单次最多 64 条）
     *
     * @param texts 文本列表
     * @return 向量列表，顺序与输入一致
     */
    public List<float[]> embedBatch(List<String> texts) {
        ensureClient();

        // 按批次处理，每批最多 64 条
        final int BATCH_SIZE = 64;
        if (texts.size() <= BATCH_SIZE) {
            return doEmbed(texts);
        }

        // 分批处理
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

        // EmbeddingResponse.getData() 返回 EmbeddingResult
        // EmbeddingResult.getData() 返回 List<Embedding>
        if (response == null || response.getData() == null
                || response.getData().getData() == null
                || response.getData().getData().isEmpty()) {
            throw new RuntimeException("智谱 embedding-3 返回空结果");
        }

        // 按 index 排序保证顺序与输入一致
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

    /** 获取向量维度 */
    public int getDimensions() {
        return DIMENSIONS;
    }

    private void ensureClient() {
        if (client == null) {
            buildClient();
        }
        if (client == null) {
            throw new RuntimeException("ZhipuEmbeddingService: API Key 未配置，无法调用 embedding");
        }
    }
}
