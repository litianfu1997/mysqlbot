-- PgVector 初始化脚本（更新版）
-- 在 PostgreSQL mysqlbot_vector 数据库中执行此脚本

-- 启用 pgvector 扩展（需要超级用户权限）
CREATE EXTENSION IF NOT EXISTS vector;

-- 删除旧的 vector_store 表（Spring AI 格式，schema 不兼容）
-- DROP TABLE IF EXISTS vector_store;

-- ===== 新版 vector_store 表 =====
-- 用于智谱 embedding-3（维度 1024）+ 自定义查询结构
CREATE TABLE IF NOT EXISTS vector_store (
    id              BIGSERIAL PRIMARY KEY,
    content         TEXT        NOT NULL,
    data_source_id  BIGINT      NOT NULL,
    doc_type        VARCHAR(20) NOT NULL,   -- schema / example
    metadata        JSONB       NOT NULL DEFAULT '{}',
    embedding       vector(1024),           -- 智谱 embedding-3 维度 1024
    created_at      TIMESTAMP   NOT NULL DEFAULT NOW()
);

-- 索引：按数据源+类型过滤
CREATE INDEX IF NOT EXISTS idx_vs_ds_type
    ON vector_store (data_source_id, doc_type);

-- 向量近似搜索索引（HNSW，余弦距离）
-- 注意: HNSW 需要至少100条数据才有优势，表为空时先跳过
-- 如数据量大时执行：
-- CREATE INDEX IF NOT EXISTS idx_vs_embedding
--     ON vector_store USING hnsw (embedding vector_cosine_ops);
