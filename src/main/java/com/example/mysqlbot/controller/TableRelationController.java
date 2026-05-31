package com.example.mysqlbot.controller;

import com.example.mysqlbot.model.TableRelation;
import com.example.mysqlbot.repository.TableRelationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/relation")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class TableRelationController {

    private final TableRelationRepository tableRelationRepository;

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
}
