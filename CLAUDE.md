# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

MySqlBot 是一个基于 LLM 工具调用的企业级 Text-to-SQL 智能数据问答系统。用户通过自然语言提问，LLM 通过工具自行探索数据库结构（表名、列信息、关联关系），生成 SQL、执行查询并可视化展示结果。

## 常用命令

### 后端 (Spring Boot)
```bash
mvn spring-boot:run          # 启动后端服务 (端口 8080)
mvn test                     # 运行测试
mvn clean package            # 构建生产包
```

### 前端 (Vue 3 + Vite)
```bash
cd frontend
npm install                  # 安装依赖
npm run dev                  # 启动开发服务器 (端口 5173)
npm run build                # 构建生产版本
```

## 架构概览

```
用户提问 → ChatService(协调器)
              ↓
         SqlGenerateService (表名清单 + Prompt组装)
              ↓
         AgentService.runAgentLoopThenStream
           ├── 工具探索轮（非流式）：LLM 调用 get_table_schema / get_table_relations 等
           └── 最终答案轮（流式）：LLM 输出 JSON {"success":true,"sql":"..."}
              ↓
         JSqlParser校验 (仅允许SELECT)
              ↓
         SqlExecuteService (动态数据源执行)
              ↓
         DataAnalysisService (结果分析 + 图表推荐)
              ↓
         返回前端渲染
```

### 核心服务职责

| 服务 | 职责 |
|------|------|
| `ChatService` | 对话协调器，串联整个工作流 |
| `SqlGenerateService` | SQL生成：注入表名清单 + 调用 AgentService |
| `AgentService` | Agent 工具循环：探索阶段非流式，最终答案流式 |
| `ToolService` | 工具实现：list_tables / get_table_schema / get_table_relations / search_tables_by_column / get_sample_data |
| `SchemaService` | 数据源同步：提取表结构 + 关系推断（喂给 get_table_relations 工具） |
| `SqlExecuteService` | SQL执行与安全校验 |
| `DataAnalysisService` | 结果分析与图表配置 |
| `LlmService` | LLM 调用门面（OpenAI 兼容 / 智谱） |

### 数据库架构 (PostgreSQL)

- **PostgreSQL (`mysqlbot`)**: 唯一数据库，存放业务表（会话、消息、数据源配置、术语表、SQL 案例、表关联关系、系统配置）。
- 无向量库依赖，schema 不再预先嵌入，改由 LLM 通过工具按需查询。

## 关键配置

配置文件: `src/main/resources/application.yml` (从 `application-template.yml` 复制)

必需环境变量:
- `DEEPSEEK_API_KEY`: LLM API密钥（默认走 DeepSeek，兼容 OpenAI 协议；可切换 OpenAI/通义千问等）
- `PG_PASSWORD`: PostgreSQL密码

启动前置: PostgreSQL ≥ 12 实例（无需 pgvector 扩展）。

## 工具调用配置

`mysqlbot.tool.max-rounds`（默认 8）：Agent 工具探索最大轮次，超出后强制输出答案。

**重要**：`data_source_id` 由后端 `AgentService` 在执行每个工具调用前自动注入，LLM 不需要也不应该传递该参数。

## 前端结构

```
frontend/src/
├── api/index.ts          # API客户端
├── store/chat.ts         # Pinia状态管理
├── views/ChatView.vue    # 主聊天界面
└── components/
    ├── ChatMessage.vue   # 消息渲染 (Markdown/SQL/表格/图表)
    └── SettingsDialog.vue # 设置弹窗
```

API代理配置: `/api` → `http://localhost:8080` (见 `vite.config.ts`)

## Prompt模板

位于 `src/main/resources/prompts/`:
- `sql-generate.st`: SQL生成主Prompt（含表名清单 + 强制工具探索协议）
- `data-analysis.st`: 数据分析与图表推荐
- `suggest-questions.st`: 追问生成

## API端点

- `/api/chat/*` - 会话与消息管理
- `/api/datasource/*` - 数据源管理、连接测试、Schema同步（同步后推断关系写入 TableRelation）
- `/api/config/*` - LLM/SQL配置

## 安全机制

1. **JSqlParser校验**: 强制仅允许SELECT语句
2. **危险关键词检测**: 拦截INSERT/UPDATE/DELETE/DROP等
3. **资源限制**: 最大1000行、30秒超时
4. **自愈重试**: SQL失败时反馈错误给LLM修正，最多3次

## 国际化

前端支持中英文切换，翻译文件位于 `frontend/src/locales/`。
