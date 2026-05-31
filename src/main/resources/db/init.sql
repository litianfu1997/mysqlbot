-- MySqlBot 数据库初始化脚本 (PostgreSQL)
-- 前置：先手工创建数据库
--   createdb -U postgres mysqlbot
-- 然后在 mysqlbot 库下执行本脚本：
--   psql -U postgres -d mysqlbot -f init.sql

-- ===== pgvector 扩展 (需要超级用户权限) =====
CREATE EXTENSION IF NOT EXISTS vector;

-- ===== 数据源配置表 =====
CREATE TABLE IF NOT EXISTS data_source (
    id               BIGSERIAL PRIMARY KEY,
    name             VARCHAR(100) NOT NULL,
    description      VARCHAR(500),
    db_type          VARCHAR(20) NOT NULL,
    host             VARCHAR(200) NOT NULL,
    port             INT NOT NULL,
    db_name          VARCHAR(100) NOT NULL,
    username         VARCHAR(100) NOT NULL,
    password         VARCHAR(500) NOT NULL,
    status           SMALLINT DEFAULT 1,
    schema_synced_at TIMESTAMP,
    created_at       TIMESTAMP DEFAULT NOW(),
    updated_at       TIMESTAMP DEFAULT NOW(),
    CONSTRAINT uk_data_source_name UNIQUE (name)
);
COMMENT ON TABLE  data_source IS '数据源配置';
COMMENT ON COLUMN data_source.db_type IS '数据库类型: postgresql';
COMMENT ON COLUMN data_source.status  IS '状态: 1=正常, 0=禁用';
COMMENT ON COLUMN data_source.schema_synced_at IS 'Schema 最后同步时间';

-- ===== LLM 配置表 =====
CREATE TABLE IF NOT EXISTS llm_config (
    id            BIGSERIAL PRIMARY KEY,
    name          VARCHAR(100) NOT NULL UNIQUE,
    base_url      VARCHAR(500) NOT NULL,
    api_key       VARCHAR(500) NOT NULL,
    model_map     JSONB,
    default_model VARCHAR(100),
    temperature   NUMERIC(3,2) DEFAULT 0.1,
    is_default    SMALLINT DEFAULT 0,
    is_enabled    SMALLINT DEFAULT 1,
    created_at    TIMESTAMP DEFAULT NOW(),
    updated_at    TIMESTAMP DEFAULT NOW()
);
COMMENT ON TABLE  llm_config IS 'LLM 配置表';
COMMENT ON COLUMN llm_config.model_map     IS '模型映射 {"别名": "实际模型名"}';
COMMENT ON COLUMN llm_config.default_model IS '默认模型别名';
COMMENT ON COLUMN llm_config.is_default    IS '是否默认配置';

-- ===== 对话会话表 =====
CREATE TABLE IF NOT EXISTS chat_session (
    id             VARCHAR(36) PRIMARY KEY,
    title          VARCHAR(200),
    data_source_id BIGINT,
    llm_config_id  BIGINT,
    created_at     TIMESTAMP DEFAULT NOW(),
    updated_at     TIMESTAMP DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_chat_session_created_at ON chat_session (created_at);
COMMENT ON TABLE  chat_session IS '对话会话';
COMMENT ON COLUMN chat_session.id IS '会话ID (UUID)';

-- ===== 对话消息表 =====
CREATE TABLE IF NOT EXISTS chat_message (
    id         BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(36) NOT NULL,
    role       VARCHAR(20) NOT NULL,
    content    TEXT NOT NULL,
    sql_query  TEXT,
    sql_result TEXT,
    error_msg  TEXT,
    thinking_content TEXT,
    created_at TIMESTAMP DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_chat_message_session_id ON chat_message (session_id);
COMMENT ON TABLE  chat_message IS '对话消息';
COMMENT ON COLUMN chat_message.role       IS '角色: user, assistant';
COMMENT ON COLUMN chat_message.sql_query  IS '生成的 SQL';
COMMENT ON COLUMN chat_message.sql_result IS 'SQL 执行结果 (JSON)';

-- ===== SQL 示例表 (用于 RAG 优化) =====
CREATE TABLE IF NOT EXISTS sql_example (
    id             BIGSERIAL PRIMARY KEY,
    data_source_id BIGINT NOT NULL,
    question       TEXT NOT NULL,
    sql_query      TEXT NOT NULL,
    description    VARCHAR(500),
    is_active      SMALLINT DEFAULT 1,
    created_at     TIMESTAMP DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_sql_example_data_source_id ON sql_example (data_source_id);
COMMENT ON TABLE sql_example IS 'SQL 示例库 (Few-shot 学习)';

-- ===== 术语表 (业务术语映射) =====
CREATE TABLE IF NOT EXISTS term_glossary (
    id             BIGSERIAL PRIMARY KEY,
    data_source_id BIGINT,
    term           VARCHAR(200) NOT NULL,
    definition     TEXT NOT NULL,
    is_active      SMALLINT DEFAULT 1,
    created_at     TIMESTAMP DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_term_glossary_data_source_id ON term_glossary (data_source_id);
COMMENT ON TABLE  term_glossary IS '业务术语表';
COMMENT ON COLUMN term_glossary.data_source_id IS '关联数据源 (NULL 表示全局)';

-- ===== 系统配置表 (Key-Value 持久化) =====
CREATE TABLE IF NOT EXISTS system_config (
    config_key   VARCHAR(100) PRIMARY KEY,
    config_value TEXT,
    description  VARCHAR(200),
    created_at   TIMESTAMP DEFAULT NOW(),
    updated_at   TIMESTAMP DEFAULT NOW()
);
COMMENT ON TABLE system_config IS '系统配置 (LLM、SQL 等运行时配置)';

-- ===== 向量存储表 (pgvector) =====
CREATE TABLE IF NOT EXISTS vector_store (
    id             BIGSERIAL    PRIMARY KEY,
    content        TEXT         NOT NULL,
    data_source_id BIGINT       NOT NULL,
    doc_type       VARCHAR(20)  NOT NULL,
    metadata       JSONB        NOT NULL DEFAULT '{}',
    embedding      vector(1024),
    created_at     TIMESTAMP    NOT NULL DEFAULT NOW()
);
COMMENT ON TABLE  vector_store IS '向量嵌入存储 (智谱 embedding-3, 维度 1024)';
COMMENT ON COLUMN vector_store.doc_type IS 'schema / example';

CREATE INDEX IF NOT EXISTS idx_vs_ds_type ON vector_store (data_source_id, doc_type);
-- 数据量较大 (>100 条) 时建议建立 HNSW 索引：
-- CREATE INDEX IF NOT EXISTS idx_vs_embedding ON vector_store USING hnsw (embedding vector_cosine_ops);

-- ===== 示例数据源 (测试用) =====
INSERT INTO data_source (name, description, db_type, host, port, db_name, username, password)
VALUES ('示例PostgreSQL数据源', '用于测试的 PostgreSQL 数据源', 'postgresql', 'localhost', 5432, 'postgres', 'postgres', 'postgres')
ON CONFLICT (name) DO NOTHING;

-- ===== 表间关系表（用于多表连接 JOIN 推断）=====
CREATE TABLE IF NOT EXISTS table_relation (
    id              BIGSERIAL PRIMARY KEY,
    data_source_id  BIGINT NOT NULL,
    from_table      VARCHAR(200) NOT NULL,
    from_column     VARCHAR(200) NOT NULL,
    to_table        VARCHAR(200) NOT NULL,
    to_column       VARCHAR(200) NOT NULL,
    source          VARCHAR(20) NOT NULL,   -- fk / naming / llm / manual
    confidence      NUMERIC(3,2) DEFAULT 1.0,
    is_active       INTEGER DEFAULT 1,
    created_at      TIMESTAMP DEFAULT NOW(),
    CONSTRAINT uk_table_relation UNIQUE (data_source_id, from_table, from_column, to_table, to_column)
);
CREATE INDEX IF NOT EXISTS idx_table_relation_ds ON table_relation (data_source_id);
COMMENT ON TABLE  table_relation IS '表间关系（JOIN 依据）：外键/命名约定/LLM推断/手动声明';
COMMENT ON COLUMN table_relation.source     IS '来源: fk=物理外键, naming=命名约定, llm=LLM推断, manual=手动声明';
COMMENT ON COLUMN table_relation.confidence IS '置信度 0.0~1.0';
