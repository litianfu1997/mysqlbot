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
    public void updateModel(@RequestParam String modelAlias) {
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
}
