package com.example.mysqlbot.service;

import com.example.mysqlbot.model.SqlExample;
import com.example.mysqlbot.model.TableRelation;
import com.example.mysqlbot.repository.TableRelationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final TableRelationRepository tableRelationRepository;

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

    /**
     * 检索图扩展：给定已召回的 schema 文档，顺关系图补充 1 跳邻接表的 schema 文档。
     * 返回包含原始文档 + 邻接表文档（去重）的合并列表。
     */
    public List<VectorStoreService.VectorSearchResult> expandWithRelations(
            Long dataSourceId,
            List<VectorStoreService.VectorSearchResult> retrievedDocs) {
        if (retrievedDocs == null || retrievedDocs.isEmpty()) {
            return retrievedDocs != null ? retrievedDocs : List.of();
        }

        // 提取已召回的表名集合
        Set<String> retrievedTableNames = retrievedDocs.stream()
                .map(doc -> (String) doc.getMetadata().getOrDefault("tableName", ""))
                .filter(s -> !s.isBlank())
                .collect(java.util.stream.Collectors.toSet());

        // 查询与这些表相关的所有关系
        List<TableRelation> relations =
                tableRelationRepository.safelyFindRelationsInvolvingTables(dataSourceId, new java.util.ArrayList<>(retrievedTableNames));

        // 找出邻接表（未在召回集中的）
        Set<String> adjacentTableNames = new java.util.LinkedHashSet<>();
        for (TableRelation r : relations) {
            if (!retrievedTableNames.contains(r.getFromTable())) adjacentTableNames.add(r.getFromTable());
            if (!retrievedTableNames.contains(r.getToTable())) adjacentTableNames.add(r.getToTable());
        }

        if (adjacentTableNames.isEmpty()) {
            log.debug("图扩展：无邻接表需要补充 (dataSourceId={})", dataSourceId);
            return retrievedDocs;
        }

        // 加载邻接表的 schema 文档
        List<VectorStoreService.VectorSearchResult> adjacentDocs =
                vectorStoreService.loadSchemaByTableNames(dataSourceId, new java.util.ArrayList<>(adjacentTableNames));
        log.debug("图扩展：补充了 {} 张邻接表 ({})", adjacentDocs.size(), adjacentTableNames);

        // 合并（原始 + 邻接，保持顺序）
        List<VectorStoreService.VectorSearchResult> combined = new java.util.ArrayList<>(retrievedDocs);
        combined.addAll(adjacentDocs);
        return combined;
    }

    /**
     * 构建表关系上下文文本，用于注入 prompt。
     * 如果 involvedTableNames 为 null 或空，返回该数据源所有激活关系。
     */
    public String buildRelationContext(Long dataSourceId, Set<String> involvedTableNames) {
        List<TableRelation> relations;
        if (involvedTableNames == null || involvedTableNames.isEmpty()) {
            relations = tableRelationRepository.findByDataSourceIdAndIsActive(dataSourceId, 1);
        } else {
            relations = tableRelationRepository.safelyFindRelationsInvolvingTables(
                    dataSourceId, new java.util.ArrayList<>(involvedTableNames));
        }

        if (relations.isEmpty()) {
            return "（无已知表间关系，多表查询时请根据字段语义自行判断 JOIN 键）";
        }

        return relations.stream()
                .map(r -> String.format("%s.%s → %s.%s  [来源:%s, 置信度:%.2f]",
                        r.getFromTable(), r.getFromColumn(),
                        r.getToTable(), r.getToColumn(),
                        r.getSource(), r.getConfidence()))
                .collect(java.util.stream.Collectors.joining("\n"));
    }
}
