package com.example.mysqlbot.controller;

import com.example.mysqlbot.model.DataSource;
import com.example.mysqlbot.repository.DataSourceRepository;
import com.example.mysqlbot.service.SchemaService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 数据源管理 API
 */
@RestController
@RequestMapping("/api/datasource")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class DataSourceController {

    private final DataSourceRepository dataSourceRepository;
    private final SchemaService schemaService;

    @GetMapping
    public List<DataSource> list() {
        return dataSourceRepository.findAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<DataSource> get(@PathVariable Long id) {
        return dataSourceRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public DataSource create(@RequestBody DataSource dataSource) {
        return dataSourceRepository.save(dataSource);
    }

    @PutMapping("/{id}")
    public ResponseEntity<DataSource> update(@PathVariable Long id, @RequestBody DataSource dataSource) {
        if (!dataSourceRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        dataSource.setId(id);
        return ResponseEntity.ok(dataSourceRepository.save(dataSource));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        dataSourceRepository.deleteById(id);
        return ResponseEntity.ok().build();
    }

    /**
     * 测试数据源连接（无需先保存）
     */
    @PostMapping("/test-connection")
    public ResponseEntity<Map<String, Object>> testAdHocConnection(@RequestBody DataSource dataSource) {
        try {
            boolean ok = schemaService.testConnection(dataSource);
            return ResponseEntity.ok(Map.of("success", ok,
                    "message", ok ? "连接成功" : "连接失败，请检查配置"));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("success", false, "message", "连接异常: " + e.getMessage()));
        }
    }

    /**
     * 测试数据源连接 (通过 ID)
     */
    @PostMapping("/{id}/test")
    public ResponseEntity<Map<String, Object>> testConnection(@PathVariable Long id) {
        return dataSourceRepository.findById(id)
                .map(ds -> {
                    try {
                        boolean ok = schemaService.testConnection(ds);
                        return ResponseEntity.ok(Map.<String, Object>of("success", ok,
                                "message", ok ? "连接成功" : "连接失败，请检查配置"));
                    } catch (Exception e) {
                        return ResponseEntity.ok(Map.<String, Object>of("success", false,
                                "message", "连接异常: " + e.getMessage()));
                    }
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 同步数据源 Schema 到向量数据库
     */
    @PostMapping("/{id}/sync-schema")
    public ResponseEntity<Map<String, Object>> syncSchema(@PathVariable Long id) {
        try {
            schemaService.syncSchema(id);
            return ResponseEntity.ok(Map.of("success", true, "message", "Schema 同步成功"));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("success", false, "message", e.getMessage()));
        }
    }
}
