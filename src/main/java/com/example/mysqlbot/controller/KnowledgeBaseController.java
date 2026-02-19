package com.example.mysqlbot.controller;

import com.example.mysqlbot.model.SqlExample;
import com.example.mysqlbot.model.TermGlossary;
import com.example.mysqlbot.repository.SqlExampleRepository;
import com.example.mysqlbot.repository.TermGlossaryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class KnowledgeBaseController {

    private final TermGlossaryRepository termGlossaryRepository;
    private final SqlExampleRepository sqlExampleRepository;
    private final com.example.mysqlbot.service.RagService ragService;

    // ===== Term Glossary =====

    @GetMapping("/terms")
    public List<TermGlossary> listTerms(@RequestParam(required = false) Long dataSourceId) {
        if (dataSourceId != null) {
            return termGlossaryRepository.findByDataSourceId(dataSourceId);
        }
        return termGlossaryRepository.findAll();
    }

    @PostMapping("/terms")
    public TermGlossary createTerm(@RequestBody TermGlossary term) {
        return termGlossaryRepository.save(term);
    }

    @DeleteMapping("/terms/{id}")
    public ResponseEntity<Void> deleteTerm(@PathVariable Long id) {
        termGlossaryRepository.deleteById(id);
        return ResponseEntity.ok().build();
    }

    // ===== SQL Examples =====

    @GetMapping("/examples")
    public List<SqlExample> listExamples(@RequestParam(required = false) Long dataSourceId) {
        if (dataSourceId != null) {
            return sqlExampleRepository.findByDataSourceId(dataSourceId);
        }
        return sqlExampleRepository.findAll();
    }

    @PostMapping("/examples")
    public SqlExample createExample(@RequestBody SqlExample example) {
        SqlExample saved = sqlExampleRepository.save(example);
        // 同步到向量库
        ragService.syncExample(saved);
        return saved;
    }

    @DeleteMapping("/examples/{id}")
    public ResponseEntity<Void> deleteExample(@PathVariable Long id) {
        sqlExampleRepository.deleteById(id);
        return ResponseEntity.ok().build();
    }
}
