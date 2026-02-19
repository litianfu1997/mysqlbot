package com.example.mysqlbot.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "data_source")
public class DataSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(name = "db_type", nullable = false, length = 20)
    private String dbType; // mysql, postgresql

    @Column(nullable = false, length = 200)
    private String host;

    @Column(nullable = false)
    private Integer port;

    @Column(name = "db_name", nullable = false, length = 100)
    private String dbName;

    @Column(nullable = false, length = 100)
    private String username;

    @Column(nullable = false, length = 500)
    private String password;

    @Column(nullable = false)
    @Builder.Default
    private Integer status = 1;

    @Column(name = "schema_synced_at")
    private LocalDateTime schemaSyncedAt;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * 构建 JDBC URL
     */
    public String buildJdbcUrl() {
        return switch (dbType.toLowerCase()) {
            case "mysql" -> String.format(
                    "jdbc:mysql://%s:%d/%s?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true",
                    host, port, dbName);
            case "postgresql" -> String.format("jdbc:postgresql://%s:%d/%s", host, port, dbName);
            default -> throw new IllegalArgumentException("不支持的数据库类型: " + dbType);
        };
    }
}
