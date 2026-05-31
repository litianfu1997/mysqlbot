package com.example.mysqlbot.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "table_relation")
public class TableRelation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "data_source_id", nullable = false)
    private Long dataSourceId;

    @Column(name = "from_table", nullable = false, length = 200)
    private String fromTable;

    @Column(name = "from_column", nullable = false, length = 200)
    private String fromColumn;

    @Column(name = "to_table", nullable = false, length = 200)
    private String toTable;

    @Column(name = "to_column", nullable = false, length = 200)
    private String toColumn;

    /**
     * 来源: fk=物理外键, naming=命名约定, llm=LLM推断, manual=手动声明
     */
    @Column(nullable = false, length = 20)
    private String source;

    /**
     * 置信度 0.0~1.0
     */
    @Column(precision = 3, scale = 2)
    @Builder.Default
    private BigDecimal confidence = BigDecimal.ONE;

    @Column(name = "is_active")
    @Builder.Default
    private Integer isActive = 1;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
