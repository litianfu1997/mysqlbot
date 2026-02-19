-- MySqlBot 数据库初始化脚本
-- 在 MySQL 中执行此脚本

CREATE DATABASE IF NOT EXISTS mysqlbot DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE mysqlbot;

-- ===== 数据源配置表 =====
CREATE TABLE IF NOT EXISTS data_source (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(100) NOT NULL COMMENT '数据源名称',
    description VARCHAR(500) COMMENT '描述',
    db_type     VARCHAR(20) NOT NULL COMMENT '数据库类型: mysql, postgresql, oracle',
    host        VARCHAR(200) NOT NULL COMMENT '主机地址',
    port        INT NOT NULL COMMENT '端口',
    db_name     VARCHAR(100) NOT NULL COMMENT '数据库名',
    username    VARCHAR(100) NOT NULL COMMENT '用户名',
    password    VARCHAR(500) NOT NULL COMMENT '密码(加密存储)',
    status      TINYINT DEFAULT 1 COMMENT '状态: 1=正常, 0=禁用',
    schema_synced_at DATETIME COMMENT 'Schema 最后同步时间',
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_name (name)
) COMMENT='数据源配置';

-- ===== 对话会话表 =====
CREATE TABLE IF NOT EXISTS chat_session (
    id          VARCHAR(36) PRIMARY KEY COMMENT '会话ID (UUID)',
    title       VARCHAR(200) COMMENT '会话标题',
    data_source_id BIGINT COMMENT '关联数据源',
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_created_at (created_at)
) COMMENT='对话会话';

-- ===== 对话消息表 =====
CREATE TABLE IF NOT EXISTS chat_message (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id  VARCHAR(36) NOT NULL COMMENT '会话ID',
    role        VARCHAR(20) NOT NULL COMMENT '角色: user, assistant',
    content     TEXT NOT NULL COMMENT '消息内容',
    sql_query   TEXT COMMENT '生成的 SQL',
    sql_result  LONGTEXT COMMENT 'SQL 执行结果 (JSON)',
    error_msg   TEXT COMMENT '错误信息',
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_session_id (session_id)
) COMMENT='对话消息';

-- ===== SQL 示例表 (用于 RAG 优化) =====
CREATE TABLE IF NOT EXISTS sql_example (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    data_source_id  BIGINT NOT NULL COMMENT '关联数据源',
    question        TEXT NOT NULL COMMENT '自然语言问题',
    sql_query       TEXT NOT NULL COMMENT '对应的 SQL',
    description     VARCHAR(500) COMMENT '说明',
    is_active       TINYINT DEFAULT 1 COMMENT '是否启用',
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_data_source_id (data_source_id)
) COMMENT='SQL 示例库 (Few-shot 学习)';

-- ===== 术语表 (业务术语映射) =====
CREATE TABLE IF NOT EXISTS term_glossary (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    data_source_id  BIGINT COMMENT '关联数据源 (NULL 表示全局)',
    term            VARCHAR(200) NOT NULL COMMENT '业务术语',
    definition      TEXT NOT NULL COMMENT '术语定义/对应的数据库字段说明',
    is_active       TINYINT DEFAULT 1,
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_data_source_id (data_source_id)
) COMMENT='业务术语表';

-- 插入示例数据源（测试用）
INSERT IGNORE INTO data_source (name, description, db_type, host, port, db_name, username, password)
VALUES ('示例MySQL数据源', '用于测试的 MySQL 数据源', 'mysql', 'localhost', 3306, 'test', 'root', 'root');

-- ===== 系统配置表 (Key-Value 持久化) =====
CREATE TABLE IF NOT EXISTS system_config (
    config_key   VARCHAR(100) PRIMARY KEY COMMENT '配置Key',
    config_value TEXT COMMENT '配置值',
    description  VARCHAR(200) COMMENT '说明',
    created_at   DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) COMMENT='系统配置 (LLM、SQL等运行时配置)';
