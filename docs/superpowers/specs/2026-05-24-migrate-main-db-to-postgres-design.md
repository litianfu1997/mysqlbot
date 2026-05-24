# 设计文档：将应用主库由 MySQL 迁移到 PostgreSQL 并与 pgvector 合一

- 日期：2026-05-24
- 范围：本仓库（`mysqlbot`）后端 + 配置 + Prompt + 文档；前端仅最小化默认值调整
- 状态：待实现

## 1. 背景与目标

当前架构存在三类数据源：

1. **应用主库**：MySQL `mysqlbot`，由 Spring Data JPA 管理业务表（会话、消息、术语、SQL 案例、数据源配置等）
2. **向量库**：PostgreSQL `mysqlbot_vector` + pgvector，存放 `vector_store`
3. **业务数据源**：用户在系统中配置、被查询的数据库（实体支持 mysql/postgresql）

本次改造统一为单一 PostgreSQL 实例：

- 应用主库由 MySQL 切换为 PostgreSQL
- 与现有 pgvector 库合并为单库单 schema（`mysqlbot` / `public`）
- **业务数据源同步去 MySQL 化**，仅保留 PostgreSQL 支持
- 不需要数据迁移（清空重建）

## 2. 终态架构

```
┌────────────────────────────────────────────────────┐
│  PostgreSQL  实例 (default: localhost:5432)        │
│                                                    │
│  Database: mysqlbot   Schema: public               │
│  ├── JPA 业务表（ddl-auto: update 自动维护）       │
│  │   ├── data_source                               │
│  │   ├── llm_config                                │
│  │   ├── chat_session                              │
│  │   ├── chat_message                              │
│  │   ├── sql_example                               │
│  │   ├── term_glossary                             │
│  │   └── system_config                             │
│  └── 向量表（VectorStoreConfig 启动时自建）        │
│      └── vector_store (pgvector extension)         │
└────────────────────────────────────────────────────┘

业务数据源（用户配置）：仅 PostgreSQL
```

## 3. 执行方式

**方案 A：一次性整体切换**（已选）

一个 PR 改完配置、Schema、代码、Prompt、文档；不保留任何 MySQL 兼容代码路径。

## 4. 变更清单

### 4.1 依赖（`pom.xml`）

- **删除** `com.mysql:mysql-connector-j`
- 保留 `org.postgresql:postgresql`（`scope=runtime`）

### 4.2 配置（`src/main/resources/application-template.yml`）

- **删除** `spring.autoconfigure.exclude`（迁移后 PgVectorStoreAutoConfiguration 仍由 `VectorStoreConfig` 绕开手动建表，但已无独立库需要 exclude，删之以减小噪声）
- **删除** `spring.datasource-pgvector` 整段
- **修改** `spring.datasource` 为 PostgreSQL 配置：
  ```yaml
  spring:
    datasource:
      url: jdbc:postgresql://localhost:5432/mysqlbot
      username: postgres
      password: ${PG_PASSWORD:postgres}
      driver-class-name: org.postgresql.Driver
      hikari:
        maximum-pool-size: 10
        minimum-idle: 2
  ```
- **修改** `spring.jpa.properties.hibernate.dialect` → `org.hibernate.dialect.PostgreSQLDialect`
- **环境变量**：移除 `MYSQL_PASSWORD`；保留 `PG_PASSWORD` 作为唯一 DB 密码

### 4.3 Schema 初始化

- **删除** `src/main/resources/db/pgvector_init.sql`
- **重写** `src/main/resources/db/init.sql` 为 PostgreSQL 方言，并合并 pgvector 段落

转换规则：

| MySQL 写法 | PostgreSQL 写法 |
|---|---|
| `BIGINT AUTO_INCREMENT PRIMARY KEY` | `BIGSERIAL PRIMARY KEY` |
| `TINYINT` | `SMALLINT` |
| `JSON` | `JSONB` |
| `DATETIME DEFAULT CURRENT_TIMESTAMP` | `TIMESTAMP DEFAULT NOW()` |
| `ON UPDATE CURRENT_TIMESTAMP` | 删除（由 JPA `@PreUpdate` 处理） |
| `LONGTEXT` | `TEXT` |
| 列级 `COMMENT '...'` | `COMMENT ON COLUMN tbl.col IS '...'` |
| 表级 `COMMENT='...'` | `COMMENT ON TABLE tbl IS '...'` |
| `UNIQUE KEY uk_name (name)` | `UNIQUE (name)` |
| 内联 `INDEX idx_x (col)` | 独立 `CREATE INDEX idx_x ON tbl(col)` |
| `CREATE DATABASE IF NOT EXISTS mysqlbot ... utf8mb4` | 注释提示手工 `createdb mysqlbot`，UTF-8 为 PG 默认 |

其它：

- 删除文末基于 `information_schema` + `PREPARE/EXECUTE` 的 MySQL 专用迁移块（清空重建无需）
- 文末追加：
  ```sql
  CREATE EXTENSION IF NOT EXISTS vector;
  CREATE TABLE IF NOT EXISTS vector_store (...);  -- 与 VectorStoreConfig 一致
  CREATE INDEX IF NOT EXISTS idx_vs_ds_type ON vector_store (data_source_id, doc_type);
  ```
- 示例数据源 INSERT：`db_type='postgresql'`, `port=5432`, `db_name='postgres'`, `username='postgres'`

### 4.4 Java 代码

| 文件 | 改动 |
|---|---|
| `model/DataSource.java` | `buildJdbcUrl()` 删除 `mysql` 分支；字段注释 `// mysql, postgresql` → `// postgresql` |
| `service/SchemaService.java` | 删除 line 90 `else if ("mysql"...)` 整段；保留默认（按 PG schema 处理）逻辑 |
| `config/VectorStoreConfig.java` | 删除 3 个 `@Value` 与 `pgVectorDataSource` Bean；`pgVectorJdbcTemplate` 改为注入 `@Primary DataSource dataSource`；保留建表逻辑；Bean 名 `pgVectorJdbcTemplate` 保留以避免改动调用方 |
| `config/DataSourceConfig.java` | 无改动 |
| `controller/DataSourceController.java` | 若存在 mysql 默认/枚举提示，改 postgresql；不存在则跳过 |

其余 Java 文件中 `mysql` 的命中绝大多数为包名 `com.example.mysqlbot`，**不动**。

### 4.5 Prompt 模板

- `prompts/sql-generate.st`：
  - `<db-engine> MySQL </db-engine>` → `PostgreSQL`
  - "引用规范（MySQL）"段重写为 PostgreSQL（双引号识别符、避免 PG 保留字 `user`/`order` 等）
- `prompts/templates/template.yaml` line 662：MySQL/Doris 反引号规则改为按业务源 `db_type` 条件应用
- `prompts/templates/sql_examples/MySQL.yaml`：**保留**（业务源是 PG 时不加载），不是必要的清理项
- 新增 `prompts/templates/sql_examples/PostgreSQL.yaml`：若该目录下已有则跳过；如缺，新建一份镜像 MySQL.yaml 结构但按 PG 方言书写的版本

### 4.6 文档

- `CLAUDE.md`：
  - "双数据库架构"段改为"单库（PostgreSQL）架构"
  - 必需环境变量删 `MYSQL_PASSWORD`
  - 启动前置：PostgreSQL ≥ 15 + pgvector 扩展
- 仓库内其他 README/文档凡涉及 MySQL 安装/初始化的，同步更新

### 4.7 前端

- 数据源新增/编辑表单的 `db_type` 默认值改为 `postgresql`
- 默认 `port` 改为 5432
- 类型下拉若仅留 PG，则改为单选/隐藏；若仍保留多种枚举（保持 UI 兼容性），仅默认值变更
- 具体改动在代码阶段视实际实现确认

## 5. 测试与验证

清空重建后逐项验证：

1. `mvn clean package` 构建通过
2. 启动 Spring Boot：
   - JPA `ddl-auto: update` 在空 PG 库中自动建 7 张业务表
   - `VectorStoreConfig` 自动建 `vector_store` 表与 `vector` 扩展
3. `psql -d mysqlbot -c "\dt"` 应见 8 张表（7 + vector_store）
4. 业务功能冒烟：
   - 新建数据源（指向一个测试 PG 业务库），调用 `POST /api/datasource/test` 连接成功
   - Schema 同步成功，向量化数据写入 `vector_store`
   - 在 UI 上新建会话 → 自然语言提问 → SQL 生成 → SELECT 执行成功 → 结果渲染
   - RAG top-k 命中合理（命中向量库中的 Schema 片段）
5. SQL 安全：尝试 `DELETE/UPDATE/INSERT/DROP` 均应被 JSqlParser 拦截

## 6. 回滚

清空重建模式下，PR 即终态。回滚 = revert PR + drop database。

## 7. 风险与开放问题

- **PostgreSQL 关键字差异**：`user`、`order` 等在 PG 是保留字，Prompt 必须明确指引 LLM 使用双引号或重命名别名。需在 sql-generate.st 中显式声明
- **`PrePersist/PreUpdate` 与 PG `TIMESTAMP` 精度**：现有实体使用 `LocalDateTime`，与 PG `TIMESTAMP` 兼容，无需调整
- **`information_schema` 差异**：SchemaService 走 JDBC `DatabaseMetaData`，PG 的 `catalog/schema` 语义与 MySQL 不同——删除 mysql 分支后默认逻辑（按 schema 取表）即为 PG 正确路径，本次设计已覆盖
- **`spring.autoconfigure.exclude` 是否真能删**：若删除后 Spring AI 自动配置仍尝试初始化 PgVectorStore Bean，并与 `VectorStoreConfig` 冲突，则恢复 exclude。代码阶段需实际启动验证
