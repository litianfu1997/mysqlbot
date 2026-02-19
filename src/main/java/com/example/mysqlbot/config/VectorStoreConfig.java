package com.example.mysqlbot.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * PgVector 数据源配置
 * 使用智谱 ZAI SDK 做嵌入，JdbcTemplate 直接操作 vector_store 表
 */
@Slf4j
@Configuration
public class VectorStoreConfig {

    @Value("${spring.datasource-pgvector.url}")
    private String pgUrl;

    @Value("${spring.datasource-pgvector.username}")
    private String pgUsername;

    @Value("${spring.datasource-pgvector.password}")
    private String pgPassword;

    /**
     * PgVector 专用数据源（与主 MySQL 数据源分离）
     */
    @Bean(name = "pgVectorDataSource")
    public DataSource pgVectorDataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(pgUrl);
        config.setUsername(pgUsername);
        config.setPassword(pgPassword);
        config.setDriverClassName("org.postgresql.Driver");
        config.setMaximumPoolSize(5);
        config.setPoolName("PgVectorPool");
        return new HikariDataSource(config);
    }

    /**
     * 给 VectorStoreService 专用的 JdbcTemplate（连接 pgvector 数据库）
     */
    @Bean(name = "pgVectorJdbcTemplate")
    public JdbcTemplate pgVectorJdbcTemplate(
            @Qualifier("pgVectorDataSource") DataSource pgVectorDataSource) {
        JdbcTemplate jt = new JdbcTemplate(pgVectorDataSource);
        initVectorStoreTable(jt);
        return jt;
    }

    /**
     * 启动时自动创建 vector_store 表（如不存在）
     * 在 pgVectorJdbcTemplate Bean 初始化时调用，确保数据源已就绪
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
