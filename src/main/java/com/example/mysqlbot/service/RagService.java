package com.example.mysqlbot.service;

import com.example.mysqlbot.model.SqlExample;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * RAG 检索服务
 * 使用智谱 embedding-3 + pgvector 实现向量检索
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagService {

    private final VectorStoreService vectorStoreService;

    @Value("${mysqlbot.rag.top-k:5}")
    private int topK;

    @Value("${mysqlbot.rag.similarity-threshold:0.3}")
    private double similarityThreshold;

    /**
     * 根据用户问题检索相关 Schema 文档
     */
    public List<VectorStoreService.VectorSearchResult> retrieveRelevantSchema(String question, Long dataSourceId) {
        log.debug("RAG Schema 检索: question='{}', dataSourceId={}", question, dataSourceId);
        List<VectorStoreService.VectorSearchResult> results = vectorStoreService.similaritySearch(question,
                dataSourceId, "schema", topK, similarityThreshold);

        if (results.isEmpty()) {
            log.warn("RAG 未检索到任何 Schema 信息 (dataSourceId={})。可能原因：1. 向量库为空（未同步）；2. 阈值 ({}) 过高；3. 问题与表结构无关。",
                    dataSourceId, similarityThreshold);
        } else {
            log.debug("RAG 检索到 {} 个相关 Schema 片段，最高相似度: {}", results.size(), results.get(0).getSimilarity());
        }
        return results;
    }

    /**
     * 将检索到的文档转换为 Prompt 上下文字符串
     */
    public String buildSchemaContext(List<VectorStoreService.VectorSearchResult> docs) {
        if (docs == null || docs.isEmpty()) {
            return "（未找到相关表结构信息）";
        }
        return docs.stream()
                .map(VectorStoreService.VectorSearchResult::getContent)
                .collect(Collectors.joining("\n---\n"));
    }

    /**
     * 检索相似的 SQL 示例 (Few-Shot)
     */
    public List<VectorStoreService.VectorSearchResult> retrieveSimilarExamples(String question, Long dataSourceId) {
        log.debug("RAG 示例检索: question='{}', dataSourceId={}", question, dataSourceId);
        List<VectorStoreService.VectorSearchResult> results = vectorStoreService.similaritySearch(question,
                dataSourceId, "example", topK, similarityThreshold);
        log.debug("RAG 检索到 {} 个相关 SQL 示例", results.size());
        return results;
    }

    /**
     * 将检索到的示例转换为上下文
     */
    public String buildExamplesContext(List<VectorStoreService.VectorSearchResult> docs) {
        if (docs == null || docs.isEmpty()) {
            return "（无参考示例）";
        }
        return docs.stream()
                .map(doc -> {
                    String q = doc.getContent();
                    String sql = (String) doc.getMetadata().getOrDefault("sql", "");
                    return String.format("Q: %s\nSQL: %s", q, sql);
                })
                .collect(Collectors.joining("\n\n"));
    }

    /**
     * 同步 SQL 示例到向量数据库
     */
    public void syncExample(SqlExample example) {
        log.info("同步 SQL 示例到向量库: {}", example.getId());
        Map<String, Object> meta = Map.of(
                "exampleId", String.valueOf(example.getId()),
                "sql", example.getSqlQuery());
        vectorStoreService.addDocument(example.getQuestion(), example.getDataSourceId(), "example", meta);
    }

    /**
     * 删除指定示例的向量数据（按 ID 匹配）
     */
    public void deleteExampleVector(Long exampleId) {
        // 简化实现：通过删除该数据源下的所有 example 类型（如需精确删除可改造 VectorStoreService）
        log.warn("deleteExampleVector: 暂不支持按单条示例删除向量，跳过 exampleId={}", exampleId);
    }
}
