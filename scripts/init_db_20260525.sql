-- ============================================
-- 数据库类型: PostgreSQL
-- 环境: dev
-- 目的: 初始化 mysqlbot 数据库 + pgvector 扩展
-- 生成时间: 2026-05-25
-- ============================================

-- 步骤1: 以下命令连接到 postgres 库执行（创建数据库）
-- CREATE DATABASE mysqlbot;

-- 步骤2: 连接到 mysqlbot 库后执行（安装向量扩展）
CREATE EXTENSION IF NOT EXISTS vector;
