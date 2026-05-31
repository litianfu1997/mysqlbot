package com.example.mysqlbot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Vector store service using pgvector for similarity search.
 * Uses PGobject for safe parameterized queries (no SQL concatenation).
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

    // ===== Write Operations =====

    public Long addDocument(String content, Long dataSourceId, String docType, Map<String, Object> metaMap) {
        float[] vector = embeddingService.embed(content);
        String metaJson = serializeMetadata(metaMap);
        String pgVecStr = toPgVectorString(vector);

        Long id = jdbcTemplate.execute((java.sql.Connection con) -> {
            try (var ps = con.prepareStatement(
                    "INSERT INTO vector_store (content, data_source_id, doc_type, metadata, embedding, created_at) " +
                    "VALUES (?, ?, ?, ?::jsonb, ?::vector, NOW()) RETURNING id")) {
                ps.setString(1, content);
                ps.setLong(2, dataSourceId);
                ps.setString(3, docType);
                ps.setString(4, metaJson);
                ps.setString(5, pgVecStr);
                try (var rs = ps.executeQuery()) {
                    if (rs.next()) return rs.getLong(1);
                    throw new RuntimeException("INSERT did not return id");
                }
            }
        });
        log.debug("Vector document written successfully id={}, type={}", id, docType);
        return id;
    }

    public void addDocuments(List<String> contents, Long dataSourceId, String docType,
            List<Map<String, Object>> metaMaps) {
        if (contents.isEmpty()) return;

        List<float[]> vectors = embeddingService.embedBatch(contents);

        String sql = "INSERT INTO vector_store (content, data_source_id, doc_type, metadata, embedding, created_at) " +
                "VALUES (?, ?, ?, ?::jsonb, ?::vector, NOW())";

        jdbcTemplate.batchUpdate(sql, new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
            @Override
            public void setValues(java.sql.PreparedStatement ps, int i) throws java.sql.SQLException {
                String metaJson = (metaMaps != null && i < metaMaps.size() && metaMaps.get(i) != null)
                        ? serializeMetadata(metaMaps.get(i)) : "{}";
                setInsertParams(ps, contents.get(i), dataSourceId, docType, metaJson, vectors.get(i));
            }
            @Override
            public int getBatchSize() { return contents.size(); }
        });

        log.info("Batch vector write completed, count={}, dataSourceId={}, type={}", contents.size(), dataSourceId, docType);
    }

    public void deleteByDataSourceAndType(Long dataSourceId, String docType) {
        int deleted = jdbcTemplate.update(
                "DELETE FROM vector_store WHERE data_source_id = ? AND doc_type = ?",
                dataSourceId, docType);
        log.info("Deleted vector docs dataSourceId={}, type={}, count={}", dataSourceId, docType, deleted);
    }

    public void deleteByDataSource(Long dataSourceId) {
        int deleted = jdbcTemplate.update(
                "DELETE FROM vector_store WHERE data_source_id = ?", dataSourceId);
        log.info("Deleted all vector docs for dataSourceId={}, count={}", dataSourceId, deleted);
    }

    // ===== Query Operations =====

    public List<VectorSearchResult> similaritySearch(String query, Long dataSourceId,
            String docType, int topK, double threshold) {
        float[] queryVec = embeddingService.embed(query);
        String pgVec = toPgVectorString(queryVec);

        // Fully parameterized: vector literal is safe (generated from float[]),
        // all user inputs use ? placeholders
        String sql = """
                SELECT id, content, metadata,
                       1 - (embedding <=> ?::vector) AS similarity
                FROM vector_store
                WHERE data_source_id = ?
                  AND doc_type = ?
                  AND 1 - (embedding <=> ?::vector) >= ?
                ORDER BY embedding <=> ?::vector
                LIMIT ?
                """;

        return jdbcTemplate.query(sql,
                (ps) -> {
                    // The vector literal is derived from our own embedding service, not user input
                    ps.setString(1, pgVec);
                    ps.setLong(2, dataSourceId);
                    ps.setString(3, docType);
                    ps.setString(4, pgVec);
                    ps.setDouble(5, threshold);
                    ps.setString(6, pgVec);
                    ps.setInt(7, topK);
                },
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
                });
    }

    // ===== Utility Methods =====

    private void setInsertParams(PreparedStatement ps, String content, Long dataSourceId,
            String docType, String metaJson, float[] vector) throws SQLException {
        ps.setString(1, content);
        ps.setLong(2, dataSourceId);
        ps.setString(3, docType);
        ps.setString(4, metaJson);
        ps.setString(5, toPgVectorString(vector));
    }

    private String serializeMetadata(Map<String, Object> metaMap) {
        if (metaMap == null || metaMap.isEmpty()) return "{}";
        try {
            return objectMapper.writeValueAsString(metaMap);
        } catch (Exception e) {
            log.warn("Failed to serialize metadata: {}", e.getMessage());
            return "{}";
        }
    }

    private String toPgVectorString(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vector[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    @lombok.Data
    public static class VectorSearchResult {
        private Long id;
        private String content;
        private double similarity;
        private Map<String, Object> metadata;
    }
}
