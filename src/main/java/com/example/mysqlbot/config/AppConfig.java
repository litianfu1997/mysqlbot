package com.example.mysqlbot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * MySqlBot 全局系统配置
 * 可以动态管理 LLM 参数、SQL 限制、RAG 策略等
 */
@Data
@Component
@ConfigurationProperties(prefix = "mysqlbot")
public class AppConfig {

    private SqlConfig sql = new SqlConfig();
    private RagConfig rag = new RagConfig();
    private LlmConfig llm = new LlmConfig();

    @Data
    public static class SqlConfig {
        /** 是否只允许 SELECT 查询 */
        private boolean allowOnlySelect = true;
        /** 最大查询行数结果限制 */
        private int maxRows = 1000;
        /** SQL 执行超时时间 (S) */
        private int timeoutSeconds = 30;
        /** 生成失败重试次数 */
        private int maxRetry = 3;
    }

    @Data
    public static class RagConfig {
        /** 是否启用 RAG（关闭后跳过向量检索，直接传空 Schema 给 LLM） */
        private boolean enabled = true;
        /** 检索 Top K 个相关文档 */
        private int topK = 5;
        /** 相似度阈值 (0.0 ~ 1.0) */
        private double similarityThreshold = 0.5;
        /** 检索策略: simple | advanced (比如加上元数据过滤) */
        private String strategy = "simple";
    }

    @Data
    public static class LlmConfig {
        /**
         * 模型名称映射
         * key: 用于前端展示的模型别名 (e.g. "GPT-4o")
         * value: 实际调用的模型名 (e.g. "gpt-4o-2024-05-13")
         */
        private Map<String, String> modelMap = new java.util.HashMap<>(Map.of(
                "DeepSeek", "deepseek-chat",
                "GPT-3.5", "gpt-3.5-turbo",
                "GPT-4", "gpt-4-turbo"));

        /** 当前默认使用的模型别名 */
        private String defaultModel = "DeepSeek";

        /** API Key */
        private String apiKey = "your-api-key";

        /** Base URL */
        private String baseUrl = "https://api.deepseek.com";

        /** 温度系数 0.0 ~ 1.0 (越低越精确，越高越有创造力) */
        private double temperature = 0.1;

        /** 自定义系统 Prompt 前缀 */
        private String systemPromptPrepy = "你是一个专业的数据分析助手。";
    }
}
