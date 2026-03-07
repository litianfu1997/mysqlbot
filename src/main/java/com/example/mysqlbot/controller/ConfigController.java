package com.example.mysqlbot.controller;

import com.example.mysqlbot.config.AppConfig;
import com.example.mysqlbot.service.ConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 系统配置 API
 * 允许前端获取和修改运行时配置
 */
@RestController
@RequestMapping("/api/config")
@RequiredArgsConstructor
@CrossOrigin
public class ConfigController {

    private final ConfigService configService;
    private final AppConfig appConfig;

    @GetMapping("/llm")
    public AppConfig.LlmConfig getLlmConfig() {
        return configService.getLlmConfig();
    }

    @PostMapping("/llm/model")
    public void updateModel(@RequestParam("modelAlias") String modelAlias) {
        configService.updateModel(modelAlias);
    }

    @PostMapping("/llm")
    public void updateLlmConfig(@RequestBody AppConfig.LlmConfig config) {
        configService.updateLlmConfig(config);
    }

    @PostMapping("/llm/test")
    public Map<String, Object> testLlmConnection(@RequestBody AppConfig.LlmConfig config) {
        boolean success = false;
        String message = "";
        try {
            success = configService.testLlmConnection(config);
            message = success ? "连接成功" : "连接失败";
        } catch (Exception e) {
            message = "连接异常: " + e.getMessage();
        }
        return Map.of("success", success, "message", message);
    }

    @GetMapping("/sql")
    public AppConfig.SqlConfig getSqlConfig() {
        return configService.getSqlConfig();
    }

    @GetMapping("/all")
    public AppConfig getAllConfig() {
        return appConfig;
    }

    // ===== 企业微信配置 =====

    @GetMapping("/wecom")
    public Map<String, String> getWeComConfig() {
        return configService.getWeComConfig();
    }

    @PostMapping("/wecom")
    public Map<String, Object> updateWeComConfig(@RequestBody Map<String, String> config) {
        try {
            configService.updateWeComConfig(config);
            return Map.of("success", true, "message", "企业微信配置已保存");
        } catch (Exception e) {
            return Map.of("success", false, "message", "保存失败: " + e.getMessage());
        }
    }

    // ===== 飞书配置 =====

    @GetMapping("/feishu")
    public Map<String, String> getFeishuConfig() {
        return configService.getFeishuConfig();
    }

    @PostMapping("/feishu")
    public Map<String, Object> updateFeishuConfig(@RequestBody Map<String, String> config) {
        try {
            configService.updateFeishuConfig(config);
            return Map.of("success", true, "message", "飞书配置已保存");
        } catch (Exception e) {
            return Map.of("success", false, "message", "保存失败: " + e.getMessage());
        }
    }
}
