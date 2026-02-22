package com.example.mysqlbot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 向量存储服务
 * 使用智谱 embedding-3 + PostgreSQL pgvector 实现向量的存储与检索
 * 通过 JdbcTemplate 直接操作 vector 类型字段，绕开 Spring AI 自动配置
 */
@Slf4j
@Service
public class VectorStoreService {

    private final ZhipuEmbeddingService embeddingService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public VectorStoreService(ZhipuEmbeddingService embeddingService,
            @Qualifier("pgVectorJdbcTemplate") JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper) {
        this.embeddingService = embeddingService;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    // ===== 写入操作 =====

    /**
     * 添加文档到向量库（自动计算 embedding）
     *
     * @param content      文本内容
     * @param dataSourceId 数据源 ID
     * @param docType      文档类型 (schema / example)
     * @param metaMap      附加元数据
     * @return 插入的记录 ID
     */
    public Long addDocument(String content, Long dataSourceId, String docType, Map<String, Object> metaMap) {
        // 1. 生成 embedding
        float[] vector = embeddingService.embed(content);
        String pgVectorStr = toPgVector(vector);

        // 2. 序列化 metadata
        String metaJson = "{}";
        if (metaMap != null && !metaMap.isEmpty()) {
            try {
                metaJson = objectMapper.writeValueAsString(metaMap);
            } catch (Exception e) {
                log.warn("序列化 metadata 失败: {}", e.getMessage());
            }
        }

        // 3. 插入数据：向量和 jsonb 内联到 SQL（JDBC PreparedStatement 不支持 ?::type 语法）
        String sql = String.format(
                "INSERT INTO vector_store (content, data_source_id, doc_type, metadata, embedding, created_at) " +
                        "VALUES (?, ?, ?, '%s'::jsonb, '%s'::vector, NOW()) RETURNING id",
                metaJson.replace("'", "''"), // 转义单引号
                pgVectorStr);
        Long id = jdbcTemplate.queryForObject(sql, Long.class,
                content, dataSourceId, docType);
        log.debug("向量文档写入成功 id={}, type={}", id, docType);
        return id;
    }

    /**
     * 批量添加文档（高效批量 embedding）
     */
    public void addDocuments(List<String> contents, Long dataSourceId, String docType,
            List<Map<String, Object>> metaMaps) {
        if (contents.isEmpty())
            return;

        // 批量 embedding
        List<float[]> vectors = embeddingService.embedBatch(contents);

        for (int i = 0; i < contents.size(); i++) {
            String pgVectorStr = toPgVector(vectors.get(i));
            String metaJson = "{}";
            if (metaMaps != null && i < metaMaps.size() && metaMaps.get(i) != null) {
                try {
                    metaJson = objectMapper.writeValueAsString(metaMaps.get(i));
                } catch (Exception e) {
                    log.warn("序列化 metadata 失败: {}", e.getMessage());
                }
            }
            // 向量和 jsonb 内联到 SQL，绕开 JDBC PreparedStatement ?::type 限制
            String sql = String.format(
                    "INSERT INTO vector_store (content, data_source_id, doc_type, metadata, embedding, created_at) " +
                            "VALUES (?, ?, ?, '%s'::jsonb, '%s'::vector, NOW())",
                    metaJson.replace("'", "''"),
                    pgVectorStr);
            jdbcTemplate.update(sql, contents.get(i), dataSourceId, docType);
        }
        log.info("批量写入向量文档完成，数量={}, dataSourceId={}, type={}", contents.size(), dataSourceId, docType);
    }

    /**
     * 删除指定数据源和类型的所有文档
     */
    public void deleteByDataSourceAndType(Long dataSourceId, String docType) {
        int deleted = jdbcTemplate.update(
                "DELETE FROM vector_store WHERE data_source_id = ? AND doc_type = ?",
                dataSourceId, docType);
        log.info("删除向量文档 dataSourceId={}, type={}, 数量={}", dataSourceId, docType, deleted);
    }

    /**
     * 删除指定数据源的所有文档
     */
    public void deleteByDataSource(Long dataSourceId) {
        int deleted = jdbcTemplate.update(
                "DELETE FROM vector_store WHERE data_source_id = ?", dataSourceId);
        log.info("删除数据源 {} 的所有向量文档，数量={}", dataSourceId, deleted);
    }

    // ===== 查询操作 =====

    /**
     * 相似度检索
     *
     * @param query        查询文本
     * @param dataSourceId 数据源 ID
     * @param docType      文档类型
     * @param topK         返回前 K 个
     * @param threshold    相似度阈值（余弦相似度，0~1）
     * @return 文档列表（按相似度从高到低）
     */
    public List<VectorSearchResult> similaritySearch(String query, Long dataSourceId,
            String docType, int topK, double threshold) {
        // 1. 生成查询向量
        float[] queryVec = embeddingService.embed(query);
        String pgVec = toPgVector(queryVec);

        // 2. 用余弦距离检索 —— 向量内联到 SQL（JDBC PreparedStatement 不支持 ?::vector 语法）
        String sql = String.format("""
                SELECT id, content, metadata,
                       1 - (embedding <=> '%s'::vector) AS similarity
                FROM vector_store
                WHERE data_source_id = ?
                  AND doc_type = ?
                  AND 1 - (embedding <=> '%s'::vector) >= ?
                ORDER BY embedding <=> '%s'::vector
                LIMIT ?
                """, pgVec, pgVec, pgVec);

        return jdbcTemplate.query(sql,
                (rs, rowNum) -> {
                    VectorSearchResult r = new VectorSearchResult();
                    r.setId(rs.getLong("id"));
                    r.setContent(rs.getString("content"));
                    r.setSimilarity(rs.getDouble("similarity"));
                    try {
                        String meta = rs.getString("metadata");
                        if (meta != null) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> m = objectMapper.readValue(meta, Map.class);
                            r.setMetadata(m);
                        }
                    } catch (Exception e) {
                        r.setMetadata(new HashMap<>());
                    }
                    return r;
                },
                dataSourceId, docType, threshold, topK);
    }

    // ===== 工具方法 =====

    /**
     * 将 float[] 转为 PostgreSQL pgvector 格式字符串
     * 例: "[0.1, 0.2, 0.3]"
     */
    private String toPgVector(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0)
                sb.append(',');
            sb.append(vector[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    // ===== 内部数据类 =====

    @lombok.Data
    public static class VectorSearchResult {
        private Long id;
        private String content;
        private double similarity;
        private Map<String, Object> metadata;
    }
}
