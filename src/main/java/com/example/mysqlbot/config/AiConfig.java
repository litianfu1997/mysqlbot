package com.example.mysqlbot.config;

import org.springframework.context.annotation.Configuration;

/**
 * AI 配置（已迁移至 ZhipuLlmService / ZhipuEmbeddingService）
 * 原 Spring AI ChatClient Bean 已不再需要，保留此类以备扩展
 */
@Configuration
public class AiConfig {
    // Spring AI ChatClient 已被 ZhipuLlmService（zai-sdk）全面替代
    // 如未来需要其他 AI 相关配置 Bean 可在此添加
}
