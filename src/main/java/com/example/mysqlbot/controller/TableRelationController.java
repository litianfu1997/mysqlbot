package com.example.mysqlbot.controller;

import com.example.mysqlbot.model.TableRelation;
import com.example.mysqlbot.repository.TableRelationRepository;
import com.example.mysqlbot.service.SchemaService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/relation")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class TableRelationController {

    private final TableRelationRepository tableRelationRepository;
    private final SchemaService schemaService;

    @GetMapping
    public List<TableRelation> list() {
        return tableRelationRepository.findAll();
    }

    @GetMapping("/datasource/{dataSourceId}")
    public List<TableRelation> listByDataSource(@PathVariable("dataSourceId") Long dataSourceId) {
        return tableRelationRepository.findByDataSourceIdAndIsActive(dataSourceId, 1);
    }

    @PostMapping
    public TableRelation create(@RequestBody TableRelation relation) {
        relation.setIsActive(1);
        relation.setSource("manual");
        return tableRelationRepository.save(relation);
    }

    @PutMapping("/{id}")
    public ResponseEntity<TableRelation> update(@PathVariable("id") Long id, @RequestBody TableRelation relation) {
        if (!tableRelationRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        relation.setId(id);
        return ResponseEntity.ok(tableRelationRepository.save(relation));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") Long id) {
        tableRelationRepository.deleteById(id);
        return ResponseEntity.ok().build();
    }

    /**
     * Runs LLM-only relation inference and returns NEW candidate relations (not persisted).
     * On failure returns {success:false, message:...} (HTTP 200) like the datasource endpoints.
     */
    @PostMapping("/generate-ai/{dataSourceId}")
    public ResponseEntity<?> generateByAi(@PathVariable("dataSourceId") Long dataSourceId) {
        try {
            return ResponseEntity.ok(schemaService.previewLlmRelations(dataSourceId));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("success", false, "message", e.getMessage()));
        }
    }

    /**
     * Persists the user-selected AI candidates (append-only, source="llm").
     */
    @PostMapping("/generate-ai/{dataSourceId}/save")
    public ResponseEntity<?> saveAiRelations(@PathVariable("dataSourceId") Long dataSourceId,
                                             @RequestBody List<TableRelation> relations) {
        try {
            return ResponseEntity.ok(schemaService.saveSelectedLlmRelations(dataSourceId, relations));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("success", false, "message", e.getMessage()));
        }
    }
}
