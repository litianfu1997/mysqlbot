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
         * 模型名称映射 (默认 DeepSeek；其他兼容 OpenAI 协议的厂商可自行替换)
         * key: 用于前端展示的模型别名 (e.g. "DeepSeek")
         * value: 实际调用的模型名 (e.g. "deepseek-chat")
         * <p>
         * 兼容 OpenAI 协议的厂商示例：
         *   - OpenAI: "GPT-4o-mini" -> "gpt-4o-mini"
         *   - 智谱:   "GLM-4"       -> "glm-4"
         */
        private Map<String, String> modelMap = new java.util.HashMap<>(Map.of(
                "DeepSeek-V4-Flash", "deepseek-v4-flash",
                "DeepSeek-V4-Pro", "deepseek-v4-pro",
                "GPT-4o-mini", "gpt-4o-mini"));

        /** 当前默认使用的模型别名 */
        private String defaultModel = "DeepSeek-V4-Flash";

        /**
         * 深度思考(推理)模型的实际模型名。
         * 当对话开启「深度思考」开关时，流式生成调用会改用该模型（默认 DeepSeek reasoner）。
         * 可替换为其他兼容 OpenAI 协议、会返回 reasoning_content 的推理模型。
         */
        private String reasoningModel = "deepseek-reasoner";

        /** API Key */
        private String apiKey = "your-api-key";

        /** Base URL；DeepSeek 官方端点（兼容 OpenAI 协议），可替换为其他厂商 */
        private String baseUrl = "https://api.deepseek.com";

        /** 温度系数 0.0 ~ 1.0 (越低越精确，越高越有创造力) */
        private double temperature = 0.1;

        /** 自定义系统 Prompt 前缀 */
        private String systemPromptPrepy = "你是一个专业的数据分析助手。";
    }
}
