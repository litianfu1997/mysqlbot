package com.example.mysqlbot.controller;

import com.example.mysqlbot.model.DataSource;
import com.example.mysqlbot.repository.DataSourceRepository;
import com.example.mysqlbot.service.SchemaService;
import com.example.mysqlbot.service.SqlExecuteService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/datasource")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class DataSourceController {

    private final DataSourceRepository dataSourceRepository;
    private final SchemaService schemaService;
    private final SqlExecuteService sqlExecuteService;

    @GetMapping
    public List<DataSource> list() {
        return dataSourceRepository.findAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<DataSource> get(@PathVariable("id") Long id) {
        return dataSourceRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public DataSource create(@RequestBody DataSource dataSource) {
        return dataSourceRepository.save(dataSource);
    }

    @PutMapping("/{id}")
    public ResponseEntity<DataSource> update(@PathVariable("id") Long id, @RequestBody DataSource dataSource) {
        if (!dataSourceRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        dataSource.setId(id);
        // Evict connection pool when data source config changes
        sqlExecuteService.evictPool(id);
        return ResponseEntity.ok(dataSourceRepository.save(dataSource));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") Long id) {
        sqlExecuteService.evictPool(id);
        dataSourceRepository.deleteById(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/test-connection")
    public ResponseEntity<Map<String, Object>> testAdHocConnection(@RequestBody DataSource dataSource) {
        try {
            boolean ok = schemaService.testConnection(dataSource);
            return ResponseEntity.ok(Map.of("success", ok,
                    "message", ok ? "Connection successful" : "Connection failed, please check config"));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("success", false, "message", "Connection error: " + e.getMessage()));
        }
    }

    @PostMapping("/{id}/test")
    public ResponseEntity<Map<String, Object>> testConnection(@PathVariable("id") Long id) {
        return dataSourceRepository.findById(id)
                .map(ds -> {
                    try {
                        boolean ok = schemaService.testConnection(ds);
                        return ResponseEntity.ok(Map.<String, Object>of("success", ok,
                                "message", ok ? "Connection successful" : "Connection failed, please check config"));
                    } catch (Exception e) {
                        return ResponseEntity.ok(Map.<String, Object>of("success", false,
                                "message", "Connection error: " + e.getMessage()));
                    }
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/sync-schema")
    public ResponseEntity<Map<String, Object>> syncSchema(@PathVariable("id") Long id) {
        try {
            schemaService.syncSchema(id);
            return ResponseEntity.ok(Map.of("success", true, "message", "Schema sync started in background"));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @GetMapping("/{id}/sync-progress")
    public ResponseEntity<SchemaService.SyncProgress> getSyncProgress(@PathVariable("id") Long id) {
        return ResponseEntity.ok(schemaService.getSyncProgress(id));
    }

    /**
     * Lists every table with its column names (fully-qualified table names),
     * used to populate the manual relation form dropdowns.
     */
    @GetMapping("/{id}/schema-tables")
    public ResponseEntity<?> getSchemaTables(@PathVariable("id") Long id) {
        try {
            return ResponseEntity.ok(schemaService.listSchemaTables(id));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("success", false, "message", e.getMessage()));
        }
    }
}
