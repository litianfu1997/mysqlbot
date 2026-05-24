# Migrate Main DB from MySQL to PostgreSQL — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate application's primary database from MySQL to PostgreSQL, merging with existing pgvector database into a single PostgreSQL instance (`mysqlbot`/`public`), and dropping MySQL support entirely from business data sources.

**Architecture:** Single PostgreSQL DB hosts both JPA business tables and `vector_store`. `VectorStoreConfig` reuses the primary `DataSource` bean. `mysql-connector-j` and all MySQL-specific code paths are removed.

**Tech Stack:** Spring Boot 3.3.5, Java 21, Spring Data JPA, Hibernate, HikariCP, PostgreSQL 15+ with pgvector extension.

**Note on testing:** This project has no unit/integration tests (`src/test/` is empty). Validation is `mvn compile` per logical commit + a final manual smoke test against a real PostgreSQL instance.

---

## File Structure

| File | Action | Responsibility |
|---|---|---|
| `pom.xml` | modify | Remove `mysql-connector-j` dependency |
| `src/main/resources/application-template.yml` | modify | Repoint primary datasource to PostgreSQL; drop pgvector-specific datasource |
| `src/main/resources/db/init.sql` | rewrite | PG-dialect DDL for all business tables + `vector_store` |
| `src/main/resources/db/pgvector_init.sql` | delete | Merged into `init.sql` |
| `src/main/java/com/example/mysqlbot/model/DataSource.java` | modify | Remove mysql branch in `buildJdbcUrl()` |
| `src/main/java/com/example/mysqlbot/service/SchemaService.java` | modify | Remove mysql branch in table name composition |
| `src/main/java/com/example/mysqlbot/config/VectorStoreConfig.java` | modify | Reuse primary `DataSource` Bean instead of independent pgvector datasource |
| `src/main/resources/prompts/sql-generate.st` | modify | db-engine → PostgreSQL; rewrite identifier-quoting rule |
| `src/main/resources/prompts/templates/template.yaml` | modify | Adjust backtick rule wording (line 662) |
| `frontend/src/components/SettingsDialog.vue` | modify | Default `dbType` → `postgresql`; remove MySQL option |
| `CLAUDE.md` | modify | Update architecture section, env vars, prereqs |

---

## Task 1: Remove MySQL driver dependency

**Files:**
- Modify: `pom.xml:71-76`

- [ ] **Step 1: Remove the MySQL connector dependency block**

Delete lines 71-76 of `pom.xml`:

```xml
        <!-- MySQL 驱动 -->
        <dependency>
            <groupId>com.mysql</groupId>
            <artifactId>mysql-connector-j</artifactId>
            <scope>runtime</scope>
        </dependency>
```

- [ ] **Step 2: Verify Maven still resolves**

Run: `mvn -q dependency:resolve`
Expected: Build success, no MySQL connector listed.

- [ ] **Step 3: Commit**

```bash
git add pom.xml
git commit -m "build: remove mysql-connector-j dependency"
```

---

## Task 2: Reconfigure application-template.yml for PostgreSQL

**Files:**
- Modify: `src/main/resources/application-template.yml`

- [ ] **Step 1: Replace the entire file with PG configuration**

Full replacement content:

```yaml
server:
  port: 8080

spring:
  application:
    name: MySqlBot

  # ===== Java 21 虚拟线程 =====
  threads:
    virtual:
      enabled: true

  # ===== 主数据库 (PostgreSQL - 业务表 + pgvector 同库) =====
  datasource:
    url: jdbc:postgresql://localhost:5432/mysqlbot
    username: postgres
    password: ${PG_PASSWORD:postgres}
    driver-class-name: org.postgresql.Driver
    hikari:
      maximum-pool-size: 10
      minimum-idle: 2

  jpa:
    hibernate:
      ddl-auto: update
    show-sql: false
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
        format_sql: true

  # ===== Spring AI - LLM 配置 =====
  ai:
    openai:
      base-url: https://open.bigmodel.cn/api/paas/v4
      api-key: ${ZHIPU_API_KEY:your_api_key_here}
      chat:
        options:
          model: glm-4
          temperature: 0.6
          max-tokens: 2048
      embedding:
        options:
          model: embedding-3

    # ===== PgVector 向量数据库 (与主库同库) =====
    vectorstore:
      pgvector:
        index-type: HNSW
        distance-type: COSINE_DISTANCE
        dimensions: 1536
        initialize-schema: true

# ===== MySqlBot 自定义配置 =====
mysqlbot:
  sql:
    allow-only-select: true
    max-rows: 1000
    timeout-seconds: 30
    max-retry: 3
  rag:
    enabled: true
    top-k: 5
    similarity-threshold: 0.5

logging:
  level:
    com.example.mysqlbot: DEBUG
    org.springframework.ai: INFO
```

Key removals vs original:
- `spring.autoconfigure.exclude` (was excluding `PgVectorStoreAutoConfiguration`)
- `spring.datasource-pgvector.*` block

Key changes:
- Primary `spring.datasource` now points at PostgreSQL `mysqlbot` DB
- JPA dialect → `PostgreSQLDialect`
- `MYSQL_PASSWORD` env var no longer referenced

- [ ] **Step 2: If a local `application.yml` exists, mirror the changes manually**

The template is what's committed; user's local `application.yml` is gitignored. If present, hand-apply the same changes.

Run: `ls src/main/resources/application.yml 2>/dev/null && echo "local config exists — sync manually" || echo "no local config — skip"`

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/application-template.yml
git commit -m "config: switch primary datasource to PostgreSQL, drop pgvector-only datasource"
```

---

## Task 3: Rewrite db/init.sql in PostgreSQL dialect

**Files:**
- Rewrite: `src/main/resources/db/init.sql`
- Delete: `src/main/resources/db/pgvector_init.sql`

- [ ] **Step 1: Replace init.sql with PG version**

Full new content:

```sql
-- MySqlBot 数据库初始化脚本 (PostgreSQL)
-- 前置：先手工创建数据库
--   createdb -U postgres mysqlbot
-- 然后在 mysqlbot 库下执行本脚本

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
COMMENT ON COLUMN data_source.status IS '状态: 1=正常, 0=禁用';

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
COMMENT ON TABLE llm_config IS 'LLM 配置表';

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
COMMENT ON TABLE chat_session IS '对话会话';

-- ===== 对话消息表 =====
CREATE TABLE IF NOT EXISTS chat_message (
    id         BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(36) NOT NULL,
    role       VARCHAR(20) NOT NULL,
    content    TEXT NOT NULL,
    sql_query  TEXT,
    sql_result TEXT,
    error_msg  TEXT,
    created_at TIMESTAMP DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_chat_message_session_id ON chat_message (session_id);
COMMENT ON TABLE chat_message IS '对话消息';

-- ===== SQL 示例表 =====
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

-- ===== 术语表 =====
CREATE TABLE IF NOT EXISTS term_glossary (
    id             BIGSERIAL PRIMARY KEY,
    data_source_id BIGINT,
    term           VARCHAR(200) NOT NULL,
    definition     TEXT NOT NULL,
    is_active      SMALLINT DEFAULT 1,
    created_at     TIMESTAMP DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_term_glossary_data_source_id ON term_glossary (data_source_id);
COMMENT ON TABLE term_glossary IS '业务术语表';

-- ===== 系统配置表 =====
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
CREATE INDEX IF NOT EXISTS idx_vs_ds_type ON vector_store (data_source_id, doc_type);
-- 数据量大时可建 HNSW 索引:
-- CREATE INDEX IF NOT EXISTS idx_vs_embedding ON vector_store USING hnsw (embedding vector_cosine_ops);

-- ===== 示例数据源 (测试用) =====
INSERT INTO data_source (name, description, db_type, host, port, db_name, username, password)
VALUES ('示例PostgreSQL数据源', '用于测试的 PostgreSQL 数据源', 'postgresql', 'localhost', 5432, 'postgres', 'postgres', 'postgres')
ON CONFLICT (name) DO NOTHING;
```

- [ ] **Step 2: Delete the old pgvector_init.sql**

Run: `rm src/main/resources/db/pgvector_init.sql`

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/db/init.sql src/main/resources/db/pgvector_init.sql
git commit -m "db: rewrite init.sql for PostgreSQL, merge pgvector_init.sql"
```

---

## Task 4: Strip MySQL branch from DataSource entity

**Files:**
- Modify: `src/main/java/com/example/mysqlbot/model/DataSource.java:29-31, 71-82`

- [ ] **Step 1: Update field comment**

Replace line 30:

```java
    private String dbType; // mysql, postgresql
```

with:

```java
    private String dbType; // postgresql
```

- [ ] **Step 2: Simplify buildJdbcUrl()**

Replace lines 71-82:

```java
    /**
     * 构建 JDBC URL
     */
    public String buildJdbcUrl() {
        return switch (dbType.toLowerCase()) {
            case "mysql" -> String.format(
                    "jdbc:mysql://%s:%d/%s?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true",
                    host, port, dbName);
            case "postgresql" -> String.format("jdbc:postgresql://%s:%d/%s", host, port, dbName);
            default -> throw new IllegalArgumentException("不支持的数据库类型: " + dbType);
        };
    }
```

with:

```java
    /**
     * 构建 JDBC URL (仅支持 PostgreSQL)
     */
    public String buildJdbcUrl() {
        if (!"postgresql".equalsIgnoreCase(dbType)) {
            throw new IllegalArgumentException("不支持的数据库类型: " + dbType + " (仅支持 postgresql)");
        }
        return String.format("jdbc:postgresql://%s:%d/%s", host, port, dbName);
    }
```

- [ ] **Step 3: Verify compile**

Run: `mvn -q compile`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/example/mysqlbot/model/DataSource.java
git commit -m "refactor(model): drop MySQL support from DataSource.buildJdbcUrl"
```

---

## Task 5: Strip MySQL branch from SchemaService

**Files:**
- Modify: `src/main/java/com/example/mysqlbot/service/SchemaService.java:87-93`

- [ ] **Step 1: Remove the mysql branch**

Replace lines 87-93:

```java
                        String fullTableName = tableName;
                        if (tableSchema != null && !tableSchema.isBlank()) {
                            fullTableName = tableSchema + "." + tableName;
                        } else if ("mysql".equalsIgnoreCase(ds.getDbType())) {
                            fullTableName = ds.getDbName() + "." + tableName;
                            tableSchema = null;
                        }
```

with:

```java
                        String fullTableName = tableName;
                        if (tableSchema != null && !tableSchema.isBlank()) {
                            fullTableName = tableSchema + "." + tableName;
                        }
```

- [ ] **Step 2: Verify compile**

Run: `mvn -q compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/example/mysqlbot/service/SchemaService.java
git commit -m "refactor(schema): drop MySQL catalog branch from SchemaService"
```

---

## Task 6: Rewire VectorStoreConfig to reuse primary DataSource

**Files:**
- Modify: `src/main/java/com/example/mysqlbot/config/VectorStoreConfig.java`

- [ ] **Step 1: Rewrite VectorStoreConfig**

Full new content:

```java
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
     * 给 VectorStoreService 使用的 JdbcTemplate (复用主数据源)。
     * Bean 名保留为 pgVectorJdbcTemplate 以避免修改调用方。
     */
    @Bean(name = "pgVectorJdbcTemplate")
    public JdbcTemplate pgVectorJdbcTemplate(DataSource dataSource) {
        JdbcTemplate jt = new JdbcTemplate(dataSource);
        initVectorStoreTable(jt);
        return jt;
    }

    private void initVectorStoreTable(JdbcTemplate jt) {
        try {
            jt.execute("CREATE EXTENSION IF NOT EXISTS vector");
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
```

- [ ] **Step 2: Verify compile**

Run: `mvn -q compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/example/mysqlbot/config/VectorStoreConfig.java
git commit -m "refactor(config): VectorStoreConfig reuses primary DataSource"
```

---

## Task 7: Update sql-generate.st prompt for PostgreSQL

**Files:**
- Modify: `src/main/resources/prompts/sql-generate.st`

- [ ] **Step 1: Change db-engine declaration**

Line 16: `<db-engine> MySQL </db-engine>` → `<db-engine> PostgreSQL </db-engine>`

- [ ] **Step 2: Rewrite identifier-quoting rule (lines 44-49)**

Replace:

```
  <rule priority="high">
    **引用规范（MySQL）：**
    1. 必须对数据库名、表名、字段名、别名外层加反引号（`）。
    2. 点号（.）不能包含在反引号内，必须写成 `schema`.`table`。
    3. 当标识符为关键字、含特殊字符或需保留大小写时必须加反引号。
  </rule>
```

with:

```
  <rule priority="high">
    **引用规范（PostgreSQL）：**
    1. 标识符默认大小写不敏感且会被折叠为小写；只有在标识符是 PG 保留字（如 user、order、group、desc、select 等）、含特殊字符或需要保留大小写时才必须使用双引号（"）包裹。
    2. 点号（.）不能包含在双引号内，必须写成 "schema"."table"。
    3. 不要使用反引号（`），反引号在 PostgreSQL 中是非法字符。
  </rule>
```

- [ ] **Step 3: Update keyword-conflict rule (line 56)**

Replace:

```
    5. 避免与 MySQL 关键字冲突（如 `order`, `group`, `desc`），如有冲突必须加反引号。
```

with:

```
    5. 避免与 PostgreSQL 保留字冲突（如 user, order, group, desc, select），如有冲突必须使用双引号包裹。
```

- [ ] **Step 4: Update format rule (line 60)**

The `CONCAT(ROUND(field * 100, 2), '%')` pattern works in PG too, but `CONCAT` returns text and PG's `ROUND(numeric, int)` is fine. Leave the rule as-is — it's compatible.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/prompts/sql-generate.st
git commit -m "prompt: switch sql-generate.st db-engine to PostgreSQL"
```

---

## Task 8: Adjust template.yaml backtick rule

**Files:**
- Modify: `src/main/resources/prompts/templates/template.yaml:662`

- [ ] **Step 1: Read the surrounding context first**

Run: read lines 655-670 of `src/main/resources/prompts/templates/template.yaml` to confirm exact wording.

- [ ] **Step 2: Replace the rule line**

Original line 662:
```
          - 如数据库引擎是 MySQL、Doris，则在表名、字段名、别名外层加反引号；
```

Replace with:
```
          - 如数据库引擎是 MySQL、Doris，则在表名、字段名、别名外层加反引号；如是 PostgreSQL，则仅在标识符为保留字或需保留大小写时用双引号包裹；
```

(Keep the MySQL/Doris half so the template system stays general; add PG guidance.)

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/prompts/templates/template.yaml
git commit -m "prompt: add PostgreSQL identifier-quoting guidance to template.yaml"
```

---

## Task 9: Update frontend defaults

**Files:**
- Modify: `frontend/src/components/SettingsDialog.vue:228, 305, 488`

- [ ] **Step 1: Read the surrounding context**

Run: read lines 220-240, 300-315, 480-495 of `frontend/src/components/SettingsDialog.vue` to confirm structure.

- [ ] **Step 2: Remove MySQL option from the type dropdown (line 228 area)**

Find:
```html
            <el-option label="MySQL" value="mysql" />
```
Delete this line (keep the PostgreSQL option that should be adjacent).

If MySQL is the only or default option, ensure a `<el-option label="PostgreSQL" value="postgresql" />` exists.

- [ ] **Step 3: Change default dbType values (lines 305 and 488)**

Replace `dbType: 'mysql'` with `dbType: 'postgresql'` at both occurrences.

Also update any companion default `port: 3306` → `port: 5432` on the same form-object if present.

- [ ] **Step 4: Quick build sanity check**

Run: `cd frontend && npm run build`
Expected: build succeeds. (If `npm install` not done, run it first.)

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/SettingsDialog.vue
git commit -m "frontend: default dbType to postgresql, remove MySQL option"
```

---

## Task 10: Update CLAUDE.md docs

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Update the "双数据库架构" section**

Replace the existing section:

```markdown
### 双数据库架构

- **MySQL (`mysqlbot`)**: 存储会话、消息、数据源配置、术语表、SQL案例
- **PostgreSQL (`mysqlbot_vector` + pgvector)**: 存储Schema/案例的向量嵌入，用于RAG检索
```

with:

```markdown
### 单库架构 (PostgreSQL)

- **PostgreSQL (`mysqlbot` + pgvector)**: 唯一数据库，同库存储业务表（会话、消息、数据源配置、术语表、SQL案例、系统配置）与向量嵌入 (`vector_store`)，无需独立向量库。
```

- [ ] **Step 2: Update required env vars**

Replace:

```markdown
必需环境变量:
- `ZHIPU_API_KEY`: LLM API密钥
- `MYSQL_PASSWORD`: MySQL密码
- `PG_PASSWORD`: PostgreSQL密码
```

with:

```markdown
必需环境变量:
- `ZHIPU_API_KEY`: LLM API密钥
- `PG_PASSWORD`: PostgreSQL密码
```

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: update CLAUDE.md for single PostgreSQL architecture"
```

---

## Task 11: Final smoke verification

- [ ] **Step 1: Build the whole project**

Run: `mvn clean package -DskipTests`
Expected: BUILD SUCCESS, no MySQL references in compiled artifact.

- [ ] **Step 2: Verify no straggling MySQL references in production code**

Run (PowerShell):
```powershell
Grep -i "mysql-connector|com\.mysql\.cj|jdbc:mysql|MySQLDialect" --include='*.java' --include='*.yml' --include='*.xml' src/main pom.xml
```
Expected: no matches. (Comments mentioning "mysql" as a historical reference are OK.)

- [ ] **Step 3: Manual smoke (user-performed, not assistant)**

Document for user:
1. Ensure a PostgreSQL ≥15 instance is running locally with the `vector` extension available.
2. `createdb -U postgres mysqlbot`
3. Apply `src/main/resources/db/init.sql` (optional — JPA `ddl-auto: update` will also build it on first start).
4. `mvn spring-boot:run`
5. Open http://localhost:5173, create a PG data source, ask a question, verify SQL generation → execution → chart render.

- [ ] **Step 4: Final commit if any docs/cleanup remain**

(Likely nothing — leave the working tree clean.)

---

## Self-Review Notes

- Spec section 4.1 (deps) → Task 1 ✓
- Spec section 4.2 (config) → Task 2 ✓
- Spec section 4.3 (init.sql) → Task 3 ✓
- Spec section 4.4 (Java code: DataSource/SchemaService/VectorStoreConfig/DataSourceConfig/DataSourceController) → Tasks 4-6 ✓ (DataSourceController/DataSourceConfig confirmed no-op via grep)
- Spec section 4.5 (prompts) → Tasks 7-8 ✓ (`PostgreSQL.yaml` confirmed already present, no new file needed)
- Spec section 4.6 (CLAUDE.md) → Task 10 ✓
- Spec section 4.7 (frontend) → Task 9 ✓
- Spec section 5 (validation) → Task 11 ✓
