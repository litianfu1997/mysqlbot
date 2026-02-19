package com.example.mysqlbot.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 向量存储实体
 * 对应 vector_store 表，存储 Schema/示例的嵌入向量
 */
@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "vector_store", indexes = {
        @Index(name = "idx_vs_ds_type", columnList = "data_source_id, doc_type")
})
public class VectorDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 原始文本内容 */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    /** 关联的数据源 ID */
    @Column(name = "data_source_id", nullable = false)
    private Long dataSourceId;

    /** 文档类型：schema / example */
    @Column(name = "doc_type", length = 20, nullable = false)
    private String docType;

    /** 附加元数据（JSON 字符串，如表名、示例 SQL 等） */
    @Column(columnDefinition = "TEXT")
    private String metadata;

    /** 嵌入向量（pgvector 格式，通过原生 SQL 写入） */
    // 这个字段通过原生 SQL 写入，JPA 层不处理
    // @Column(name = "embedding", columnDefinition = "vector(1024)")

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
    }
}
