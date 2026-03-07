package com.example.mysqlbot.controller;

import com.example.mysqlbot.model.LlmConfig;
import com.example.mysqlbot.service.LlmConfigService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * LLM配置管理 API
 */
@RestController
@RequestMapping("/api/llm-config")
@RequiredArgsConstructor
@CrossOrigin
public class LlmConfigController {

    private final LlmConfigService llmConfigService;

    /**
     * 获取所有LLM配置列表
     */
    @GetMapping
    public List<LlmConfig> getAllConfigs() {
        return llmConfigService.getAllConfigs();
    }

    /**
     * 获取所有启用的LLM配置
     */
    @GetMapping("/enabled")
    public List<LlmConfig> getEnabledConfigs() {
        return llmConfigService.getEnabledConfigs();
    }

    /**
     * 根据ID获取LLM配置
     */
    @GetMapping("/{id}")
    public ResponseEntity<LlmConfig> getConfigById(@PathVariable Long id) {
        return llmConfigService.getConfigById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 获取默认LLM配置
     */
    @GetMapping("/default")
    public ResponseEntity<LlmConfig> getDefaultConfig() {
        return llmConfigService.getDefaultConfig()
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 创建新的LLM配置
     */
    @PostMapping
    public ResponseEntity<LlmConfig> createConfig(@RequestBody LlmConfigRequest request) {
        try {
            LlmConfig config = LlmConfig.builder()
                    .name(request.getName())
                    .baseUrl(request.getBaseUrl())
                    .apiKey(request.getApiKey())
                    .modelMap(request.getModelMap())
                    .defaultModel(request.getDefaultModel())
                    .temperature(request.getTemperature())
                    .isDefault(request.getIsDefault())
                    .isEnabled(request.getIsEnabled())
                    .build();
            return ResponseEntity.ok(llmConfigService.createConfig(config));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * 更新LLM配置
     */
    @PutMapping("/{id}")
    public ResponseEntity<LlmConfig> updateConfig(@PathVariable Long id, @RequestBody LlmConfigRequest request) {
        try {
            LlmConfig config = LlmConfig.builder()
                    .name(request.getName())
                    .baseUrl(request.getBaseUrl())
                    .apiKey(request.getApiKey())
                    .modelMap(request.getModelMap())
                    .defaultModel(request.getDefaultModel())
                    .temperature(request.getTemperature())
                    .isDefault(request.getIsDefault())
                    .isEnabled(request.getIsEnabled())
                    .build();
            return ResponseEntity.ok(llmConfigService.updateConfig(id, config));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * 设置默认配置
     */
    @PostMapping("/{id}/set-default")
    public ResponseEntity<Void> setDefault(@PathVariable Long id) {
        try {
            llmConfigService.setDefault(id);
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 删除LLM配置
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteConfig(@PathVariable Long id) {
        try {
            llmConfigService.deleteConfig(id);
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * 测试LLM连接
     */
    @PostMapping("/test")
    public Map<String, Object> testConnection(@RequestBody LlmConfigRequest request) {
        LlmConfig config = LlmConfig.builder()
                .name(request.getName())
                .baseUrl(request.getBaseUrl())
                .apiKey(request.getApiKey())
                .modelMap(request.getModelMap())
                .defaultModel(request.getDefaultModel())
                .temperature(request.getTemperature())
                .build();

        boolean success = false;
        String message = "";
        try {
            success = llmConfigService.testConnection(config);
            message = success ? "连接成功" : "连接失败";
        } catch (Exception e) {
            message = "连接异常: " + e.getMessage();
        }
        return Map.of("success", success, "message", message);
    }

    /**
     * LLM配置请求DTO
     */
    @Data
    public static class LlmConfigRequest {
        private String name;
        private String baseUrl;
        private String apiKey;
        private Map<String, String> modelMap;
        private String defaultModel;
        private java.math.BigDecimal temperature;
        private Boolean isDefault;
        private Boolean isEnabled = true;
    }
}
