package com.example.mysqlbot.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * pgvector 表初始化与 JdbcTemplate Bean。
 * 复用主 DataSource —— 业务表与 vector_store 同库 (PostgreSQL)。
 */
@Slf4j
@Configuration
public class VectorStoreConfig {

    /**
     * 给 VectorStoreService 使用的 JdbcTemplate（复用主数据源）。
     * Bean 名保留为 pgVectorJdbcTemplate 以避免修改调用方。
     */
    @Bean(name = "pgVectorJdbcTemplate")
    public JdbcTemplate pgVectorJdbcTemplate(DataSource dataSource) {
        JdbcTemplate jt = new JdbcTemplate(dataSource);
        initVectorStoreTable(jt);
        return jt;
    }

    /**
     * 启动时自动创建 vector_store 表（如不存在）。
     */
    private void initVectorStoreTable(JdbcTemplate jt) {
        try {
            // 确保 pgvector 扩展已安装
            jt.execute("CREATE EXTENSION IF NOT EXISTS vector");

            // 创建 vector_store 表（维度固定 1024，对应智谱 embedding-3）
            jt.execute("""
                    CREATE TABLE IF NOT EXISTS vector_store (
                        id             BIGSERIAL    PRIMARY KEY,
                        content        TEXT         NOT NULL,
                        data_source_id BIGINT       NOT NULL,
                        doc_type       VARCHAR(20)  NOT NULL,
                        metadata       JSONB        NOT NULL DEFAULT '{}',
                        embedding      vector(1024),
                        created_at     TIMESTAMP    NOT NULL DEFAULT NOW()
                    )
                    """);

            // 创建联合索引（按数据源+类型过滤）
            jt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_vs_ds_type
                        ON vector_store (data_source_id, doc_type)
                    """);

            log.info("VectorStoreConfig: vector_store 表初始化完成");
        } catch (Exception e) {
            log.warn("VectorStoreConfig: vector_store 表初始化警告: {}", e.getMessage());
        }
    }
}
