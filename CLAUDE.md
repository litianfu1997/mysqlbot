# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

MySqlBot 是一个基于 LLM + RAG 的企业级 Text-to-SQL 智能数据问答系统。用户通过自然语言提问，系统自动生成 SQL、执行查询并可视化展示结果。

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
         SqlGenerateService (RAG检索 + Prompt组装 + LLM调用)
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
| `SqlGenerateService` | SQL生成，整合RAG/术语/案例 |
| `RagService` | Schema向量检索 |
| `VectorStoreService` | pgvector操作 |
| `SqlExecuteService` | SQL执行与安全校验 |
| `DataAnalysisService` | 结果分析与图表配置 |
| `ZhipuLlmService` | LLM调用 (OpenAI协议) |
| `ZhipuEmbeddingService` | 文本向量化 |

### 单库架构 (PostgreSQL)

- **PostgreSQL (`mysqlbot` + pgvector)**: 唯一数据库。同库下既存放业务表（会话、消息、数据源配置、术语表、SQL 案例、系统配置），也存放向量嵌入（`vector_store`）。无需独立向量库。

## 关键配置

配置文件: `src/main/resources/application.yml` (从 `application-template.yml` 复制)

必需环境变量:
- `DEEPSEEK_API_KEY`: LLM API密钥（默认走 DeepSeek，兼容 OpenAI 协议；base-url 可切换 OpenAI/智谱/通义千问等）
- `PG_PASSWORD`: PostgreSQL密码

启动前置: PostgreSQL ≥ 15 实例 + 已安装 `pgvector` 扩展（`CREATE EXTENSION vector;` 需要超级用户权限）。

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
- `sql-generate.st`: SQL生成主Prompt
- `data-analysis.st`: 数据分析与图表推荐
- `suggest-questions.st`: 追问生成

## API端点

- `/api/chat/*` - 会话与消息管理
- `/api/datasource/*` - 数据源管理、连接测试、Schema同步
- `/api/config/*` - LLM/SQL配置

## 安全机制

1. **JSqlParser校验**: 强制仅允许SELECT语句
2. **危险关键词检测**: 拦截INSERT/UPDATE/DELETE/DROP等
3. **资源限制**: 最大1000行、30秒超时
4. **自愈重试**: SQL失败时反馈错误给LLM修正，最多3次

## 国际化

前端支持中英文切换，翻译文件位于 `frontend/src/locales/`。
