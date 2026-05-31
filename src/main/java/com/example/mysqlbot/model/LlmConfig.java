package com.example.mysqlbot.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "llm_config")
public class LlmConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, unique = true, length = 100)
    private String name;

    @Column(name = "base_url", nullable = false, length = 500)
    private String baseUrl;

    @Column(name = "api_key", nullable = false, length = 500)
    private String apiKey;

    @Column(name = "model_map", columnDefinition = "TEXT")
    @Convert(converter = ModelMapConverter.class)
    private Map<String, String> modelMap = new HashMap<>();

    @Column(name = "default_model", length = 100)
    private String defaultModel;

    @Column(name = "temperature", precision = 3, scale = 2)
    private BigDecimal temperature = BigDecimal.valueOf(0.1);

    @Column(name = "is_default")
    private Boolean isDefault = false;

    @Column(name = "is_enabled")
    private Boolean isEnabled = true;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (modelMap == null) {
            modelMap = new HashMap<>();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * JPA Converter for Map<String, String> to JSON
     */
    @Converter
    public static class ModelMapConverter implements AttributeConverter<Map<String, String>, String> {
        private static final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

        @Override
        public String convertToDatabaseColumn(Map<String, String> attribute) {
            if (attribute == null || attribute.isEmpty()) {
                return null;
            }
            try {
                return objectMapper.writeValueAsString(attribute);
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        public Map<String, String> convertToEntityAttribute(String dbData) {
            if (dbData == null || dbData.isBlank()) {
                return new HashMap<>();
            }
            try {
                return objectMapper.readValue(dbData, new com.fasterxml.jackson.core.type.TypeReference<>() {});
            } catch (Exception e) {
                return new HashMap<>();
            }
        }
    }
}

