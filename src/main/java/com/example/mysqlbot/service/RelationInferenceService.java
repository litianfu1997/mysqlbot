package com.example.mysqlbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Service responsible for inferring table relationships via three strategies:
 * physical foreign keys, naming conventions, and LLM-assisted inference.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RelationInferenceService {

    private final ObjectMapper objectMapper;

    // -------------------------------------------------------------------------
    // Inner data structures
    // -------------------------------------------------------------------------

    /** Metadata for a single database table, collected during schema sync. */
    public static class TableMeta {
        public String tableName;          // fully-qualified table name (matches vector_store)
        public String simpleTableName;    // bare table name without schema prefix
        public List<String> columns = new ArrayList<>();
        public List<String> primaryKeys = new ArrayList<>();
        public List<ForeignKeyInfo> importedKeys = new ArrayList<>();
    }

    /** A single physical foreign-key constraint read from JDBC getImportedKeys. */
    public static class ForeignKeyInfo {
        public String fkColumn;   // column in the referencing table
        public String pkTable;    // fully-qualified name of the referenced table
        public String pkColumn;   // column in the referenced table

        public ForeignKeyInfo(String fkColumn, String pkTable, String pkColumn) {
            this.fkColumn = fkColumn;
            this.pkTable = pkTable;
            this.pkColumn = pkColumn;
        }
    }

    /** A single inferred relationship, ready to be saved as a TableRelation entity. */
    public static class InferredRelation {
        private String fromTable;
        private String fromColumn;
        private String toTable;
        private String toColumn;
        private String source;        // "fk" / "naming" / "llm"
        private BigDecimal confidence;

        public InferredRelation(String fromTable, String fromColumn,
                                String toTable, String toColumn,
                                String source, BigDecimal confidence) {
            this.fromTable = fromTable;
            this.fromColumn = fromColumn;
            this.toTable = toTable;
            this.toColumn = toColumn;
            this.source = source;
            this.confidence = confidence;
        }

        public String getFromTable()     { return fromTable; }
        public String getFromColumn()    { return fromColumn; }
        public String getToTable()       { return toTable; }
        public String getToColumn()      { return toColumn; }
        public String getSource()        { return source; }
        public BigDecimal getConfidence(){ return confidence; }
    }

    // -------------------------------------------------------------------------
    // Method 1: Physical foreign keys
    // -------------------------------------------------------------------------

    /**
     * Converts every physical foreign-key constraint already loaded into each
     * {@link TableMeta} into an {@link InferredRelation} with source="fk" and
     * confidence=1.0.
     */
    public List<InferredRelation> inferFromForeignKeys(List<TableMeta> tables) {
        List<InferredRelation> result = new ArrayList<>();
        for (TableMeta meta : tables) {
            if (meta.importedKeys == null) continue;
            for (ForeignKeyInfo fk : meta.importedKeys) {
                result.add(new InferredRelation(
                        meta.tableName,
                        fk.fkColumn,
                        fk.pkTable,
                        fk.pkColumn,
                        "fk",
                        BigDecimal.ONE
                ));
            }
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Method 2: Naming-convention inference
    // -------------------------------------------------------------------------

    /**
     * Infers relationships from column naming patterns (e.g. {@code order.user_id}
     * → {@code user.id}).  Only underscore-style {@code _id} suffixes are processed.
     * Deduplicates by (fromTable, fromColumn, toTable, toColumn).
     */
    public List<InferredRelation> inferFromNamingConventions(List<TableMeta> tables) {
        // Build lookup maps for quick access
        // simpleTableName (lower) → TableMeta
        Map<String, TableMeta> bySimpleName = new HashMap<>();
        for (TableMeta t : tables) {
            bySimpleName.put(t.simpleTableName.toLowerCase(), t);
        }

        Map<String, InferredRelation> dedup = new LinkedHashMap<>();

        for (TableMeta table : tables) {
            Set<String> pkSet = new HashSet<>(table.primaryKeys);

            for (String col : table.columns) {
                // Skip if the column is itself a primary key of this table
                if (pkSet.contains(col)) continue;

                String colLower = col.toLowerCase();

                // Strategy A: columns ending with "_id" (but not equal to "id")
                if (colLower.endsWith("_id") && !colLower.equals("_id")) {
                    String prefix = colLower.substring(0, colLower.length() - 3); // strip "_id"

                    TableMeta candidate = bySimpleName.get(prefix);
                    if (candidate == null) {
                        // try plural form: prefix + "s"
                        candidate = bySimpleName.get(prefix + "s");
                    }

                    if (candidate != null && !candidate.primaryKeys.isEmpty()) {
                        String pk = candidate.primaryKeys.get(0);
                        String dedupKey = table.tableName + "|" + col + "|" + candidate.tableName + "|" + pk;
                        dedup.putIfAbsent(dedupKey, new InferredRelation(
                                table.tableName, col,
                                candidate.tableName, pk,
                                "naming",
                                new BigDecimal("0.70")
                        ));
                    }
                }

                // Strategy B: col matches another table's primary key name exactly
                // e.g. another table has pk "order_id" and this table also has a column "order_id"
                for (TableMeta other : tables) {
                    if (other.tableName.equals(table.tableName)) continue;
                    if (other.primaryKeys.contains(col)) {
                        String dedupKey = table.tableName + "|" + col + "|" + other.tableName + "|" + col;
                        dedup.putIfAbsent(dedupKey, new InferredRelation(
                                table.tableName, col,
                                other.tableName, col,
                                "naming",
                                new BigDecimal("0.65")
                        ));
                    }
                }
            }
        }

        return new ArrayList<>(dedup.values());
    }

    // -------------------------------------------------------------------------
    // Method 3: LLM-assisted inference
    // -------------------------------------------------------------------------

    /**
     * Calls the LLM with a schema summary and parses its JSON response into
     * {@link InferredRelation} objects with source="llm".
     * <p>
     * All exceptions are caught and logged as warnings — a failure here must
     * never abort the schema-sync process.
     */
    public List<InferredRelation> inferFromLlm(List<TableMeta> tables, LlmService llmService, String dbEngine) {
        try {
            // 1. Build schemaInfo text (capped at 8000 chars)
            StringBuilder schemaInfo = new StringBuilder();
            for (TableMeta t : tables) {
                String block = "Table: " + t.tableName + "\n"
                        + "  Columns: " + String.join(", ", t.columns) + "\n"
                        + "  PrimaryKey: " + String.join(", ", t.primaryKeys) + "\n";
                if (schemaInfo.length() + block.length() > 8000) break;
                schemaInfo.append(block);
            }

            // 2. Load prompt template and substitute placeholders
            String template = loadPromptTemplate();
            String prompt = template
                    .replace("{dbEngine}", dbEngine == null ? "Unknown" : dbEngine)
                    .replace("{schemaInfo}", schemaInfo.toString());

            // 3. Call LLM (low temperature for deterministic output)
            String response = llmService.chat(prompt, 0.1);
            if (response == null || response.isBlank()) {
                return Collections.emptyList();
            }

            // 4. Parse JSON — strip any accidental markdown fences
            String json = extractJsonArray(response.trim());

            JsonNode arr = objectMapper.readTree(json);
            if (!arr.isArray()) {
                log.warn("RelationInferenceService: LLM returned non-array JSON, skipping");
                return Collections.emptyList();
            }

            // Build a set of known table names for validation
            Set<String> knownTableNames = new HashSet<>();
            for (TableMeta t : tables) {
                knownTableNames.add(t.tableName);
                knownTableNames.add(t.simpleTableName);
            }

            List<InferredRelation> result = new ArrayList<>();
            for (JsonNode node : arr) {
                double confidence = node.path("confidence").asDouble(0.0);
                if (confidence < 0.6) continue;

                String fromTable  = node.path("from_table").asText(null);
                String fromColumn = node.path("from_column").asText(null);
                String toTable    = node.path("to_table").asText(null);
                String toColumn   = node.path("to_column").asText(null);

                if (fromTable == null || fromColumn == null || toTable == null || toColumn == null) continue;

                // Validate table names exist in our schema
                if (!knownTableNames.contains(fromTable) && !knownTableNames.contains(fromTable.toLowerCase())) continue;
                if (!knownTableNames.contains(toTable) && !knownTableNames.contains(toTable.toLowerCase())) continue;

                // Resolve to the fully-qualified table name if only the simple name was returned
                String resolvedFromTable = resolveTableName(fromTable, tables);
                String resolvedToTable   = resolveTableName(toTable, tables);

                result.add(new InferredRelation(
                        resolvedFromTable, fromColumn,
                        resolvedToTable, toColumn,
                        "llm",
                        BigDecimal.valueOf(confidence)
                ));
            }
            return result;

        } catch (Exception e) {
            log.warn("RelationInferenceService: LLM inference failed, skipping. Reason: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String loadPromptTemplate() throws IOException {
        ClassPathResource resource = new ClassPathResource("prompts/relation-infer.st");
        return resource.getContentAsString(StandardCharsets.UTF_8);
    }

    /**
     * Strips markdown code fences that LLMs sometimes add around JSON output.
     */
    private String extractJsonArray(String raw) {
        if (raw.startsWith("```")) {
            int firstNewline = raw.indexOf('\n');
            if (firstNewline >= 0) raw = raw.substring(firstNewline + 1);
            if (raw.endsWith("```")) raw = raw.substring(0, raw.lastIndexOf("```")).trim();
        }
        int start = raw.indexOf('[');
        int end   = raw.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return raw.substring(start, end + 1);
        }
        return raw;
    }

    /**
     * Given a table name returned by the LLM (which may be the simple name),
     * resolves it to the fully-qualified name stored in {@link TableMeta#tableName}.
     */
    private String resolveTableName(String name, List<TableMeta> tables) {
        // Exact match against fully-qualified name first
        for (TableMeta t : tables) {
            if (t.tableName.equals(name) || t.tableName.equalsIgnoreCase(name)) {
                return t.tableName;
            }
        }
        // Fall back to simple name match
        for (TableMeta t : tables) {
            if (t.simpleTableName.equals(name) || t.simpleTableName.equalsIgnoreCase(name)) {
                return t.tableName;
            }
        }
        // Return as-is if no match (validation already filtered unknowns above)
        return name;
    }
}
