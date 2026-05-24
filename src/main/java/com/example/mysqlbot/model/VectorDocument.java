package com.example.mysqlbot.model;

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
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VectorDocument {

    private Long id;

    private String content;
    private Long dataSourceId;
    private String docType;
    private String metadata;
    private LocalDateTime createdAt;
}
