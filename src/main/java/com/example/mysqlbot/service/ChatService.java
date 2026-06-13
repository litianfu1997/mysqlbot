package com.example.mysqlbot.service;

import com.example.mysqlbot.model.ChatMessage;
import com.example.mysqlbot.model.ChatSession;
import com.example.mysqlbot.model.DataSource;
import com.example.mysqlbot.model.LlmConfig;
import com.example.mysqlbot.repository.ChatMessageRepository;
import com.example.mysqlbot.repository.ChatSessionRepository;
import com.example.mysqlbot.repository.LlmConfigRepository;
import com.example.mysqlbot.repository.DataSourceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.stream.Collectors;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.DriverManager;
import com.example.mysqlbot.security.SecurityContext;
import java.util.function.Consumer;

/**
 * Chat management service handling multi-turn conversations, session management, and message storage.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final SqlGenerateService sqlGenerateService;
    private final SqlExecuteService sqlExecuteService;
    private final ChartAgentService chartAgentService;
    private final SuggestQuestionService suggestQuestionService;
    private final SqlPermissionService sqlPermissionService;
    private final LlmConfigRepository llmConfigRepository;
    private final DataSourceRepository dataSourceRepository;
    private final ObjectMapper objectMapper;

    /**
     * Stream event record for SSE.
     */
    public record StreamEvent(String type, Object data) {}

    @Transactional
    public ChatSession createSession(Long dataSourceId, String title) {
        return createSession(dataSourceId, title, null);
    }

    @Transactional
    public ChatSession createSession(Long dataSourceId, String title, Long llmConfigId) {
        Long effectiveLlmConfigId = llmConfigId;
        if (effectiveLlmConfigId == null) {
            Optional<LlmConfig> defaultConfig = llmConfigRepository.findByIsDefaultTrue();
            effectiveLlmConfigId = defaultConfig.map(LlmConfig::getId).orElse(null);
        }

        ChatSession session = ChatSession.builder()
                .title(title != null ? title : "New Chat")
                .dataSourceId(dataSourceId)
                .llmConfigId(effectiveLlmConfigId)
                .build();
        return sessionRepository.save(session);
    }

    /**
     * Process user message (synchronous, backward compatible).
     */
    @Transactional
    public ChatMessage chat(String sessionId, String userQuestion) {
        ChatSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("Session not found: " + sessionId));

        LlmConfig llmConfig = resolveLlmConfig(session);

        // Save user message
        ChatMessage userMsg = ChatMessage.builder()
                .sessionId(sessionId)
                .role("user")
                .content(userQuestion)
                .build();
        messageRepository.save(userMsg);

        List<ChatMessage> conversation = buildConversation(sessionId);

        // SQL generation with retry (multi-turn self-healing via appending to the conversation)
        SqlGenerateService.SqlGenerateResult generateResult = null;
        SqlExecuteService.SqlExecuteResult executeResult = null;
        String lastErrorMsg = null;

        int maxRetry = sqlGenerateService.getMaxRetry();
        for (int i = 0; i < maxRetry; i++) {
            if (i > 0) {
                log.info("SQL execution failed, retry #{}: {}", i, lastErrorMsg);
                ChatMessage failedAssistant = ChatMessage.builder()
                        .role("assistant")
                        .content(generateResult != null ? generateResult.getExplanation() : "")
                        .sqlQuery(generateResult != null ? generateResult.getSql() : null)
                        .build();
                ChatMessage retryUser = ChatMessage.builder()
                        .role("user")
                        .content("上一条 SQL 执行失败：" + lastErrorMsg + "，请修正后重新输出 JSON。")
                        .build();
                conversation.add(failedAssistant);
                conversation.add(retryUser);
            }

            generateResult = sqlGenerateService.generate(userQuestion, session.getDataSourceId(), conversation, llmConfig);
            if (!generateResult.isSuccess() || generateResult.getSql() == null) break;

            String finalSql = applyPermission(generateResult.getSql(), session.getDataSourceId(), llmConfig);
            executeResult = sqlExecuteService.execute(finalSql, session.getDataSourceId());
            if (executeResult.isSuccess()) break;
            lastErrorMsg = executeResult.getErrorMessage();
        }

        ChatMessage assistantMsg = buildAssistantMessage(sessionId, userQuestion, generateResult, executeResult, llmConfig);
        messageRepository.save(assistantMsg);
        updateSessionTitle(session, userQuestion);
        return assistantMsg;
    }

    /**
     * Process user message with SSE streaming.
     * Emits real-time events including LLM thinking tokens and content tokens.
     */
    @Transactional
    public void chatStream(String sessionId, String userQuestion, boolean thinking, Consumer<StreamEvent> emitter) {
        ChatSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("Session not found: " + sessionId));

        LlmConfig llmConfig = resolveLlmConfig(session);

        // Save user message
        ChatMessage userMsg = ChatMessage.builder()
                .sessionId(sessionId)
                .role("user")
                .content(userQuestion)
                .build();
        messageRepository.save(userMsg);

        emitter.accept(new StreamEvent("user_message", userMsg));

        List<ChatMessage> conversation = buildConversation(sessionId);

        // thinkingContent holds ONLY the model's real reasoning_content (deep-thinking mode),
        // not the canned status messages, so the persisted/displayed thinking stays clean.
        StringBuilder thinkingContent = new StringBuilder();
        Consumer<String> progress = message -> emitProgress(emitter, message);

        // Step 1: Generate SQL with streaming
        progress.accept("收到问题，正在理解查询意图...");
        progress.accept("正在检索相关表结构和业务术语...");

        SqlGenerateService.SqlGenerateResult generateResult = null;
        SqlExecuteService.SqlExecuteResult executeResult = null;
        String lastErrorMsg = null;

        int maxRetry = sqlGenerateService.getMaxRetry();
        for (int i = 0; i < maxRetry; i++) {
            if (i > 0) {
                log.info("SQL execution failed, retry #{}: {}", i, lastErrorMsg);
                ChatMessage failedAssistant = ChatMessage.builder()
                        .role("assistant")
                        .content(generateResult != null ? generateResult.getExplanation() : "")
                        .sqlQuery(generateResult != null ? generateResult.getSql() : null)
                        .build();
                ChatMessage retryUser = ChatMessage.builder()
                        .role("user")
                        .content("上一条 SQL 执行失败：" + lastErrorMsg + "，请修正后重新输出 JSON。")
                        .build();
                conversation.add(failedAssistant);
                conversation.add(retryUser);
                progress.accept("SQL 执行失败，正在第 " + (i + 1) + " 次修正...");
            }

            progress.accept("正在调用模型生成 SQL...");
            generateResult = sqlGenerateService.generateStream(
                    userQuestion, session.getDataSourceId(), conversation, llmConfig, thinking,
                    (type, token) -> {
                        if ("thinking".equals(type)) {
                            thinkingContent.append(token);
                        }
                        emitter.accept(new StreamEvent(type, token));
                    }
            );

            if (!generateResult.isSuccess() || generateResult.getSql() == null) break;

            progress.accept("SQL 已生成，正在进行权限规则和安全校验...");
            emitter.accept(new StreamEvent("sql_generated", Map.of(
                    "sql", generateResult.getSql(),
                    "explanation", generateResult.getExplanation() != null ? generateResult.getExplanation() : ""
            )));

            String finalSql = applyPermission(generateResult.getSql(), session.getDataSourceId(), llmConfig);

            // Step 2: Execute SQL
            progress.accept("正在执行 SQL 并读取结果...");
            executeResult = sqlExecuteService.execute(finalSql, session.getDataSourceId());
            if (executeResult.isSuccess()) break;
            lastErrorMsg = executeResult.getErrorMessage();
        }

        boolean needClarification = generateResult != null && generateResult.isNeedClarification();

        List<String> suggestedQuestions = List.of();
        ChartAgentService.ChartResult chart = null;
        if (!needClarification && generateResult != null && generateResult.isSuccess()
                && executeResult != null && executeResult.isSuccess()) {
            emitter.accept(new StreamEvent("sql_executed", executeResult));
            List<Map<String, Object>> rows = executeResult.getRows();

            // Step 3: 自动出图 — 制图 Agent 探查数据形态并直出 ECharts 配置
            try {
                progress.accept("正在分析数据并生成图表...");
                chart = chartAgentService.generate(userQuestion, generateResult.getSql(), rows, llmConfig);
                Map<String, Object> analysisPayload = new java.util.HashMap<>();
                analysisPayload.put("insight", chart.getInsight());
                analysisPayload.put("chartType", chart.getChartType());
                analysisPayload.put("chartOption", chart.getChartOption());
                emitter.accept(new StreamEvent("analysis", analysisPayload));
            } catch (Exception e) {
                log.error("Failed to generate chart", e);
            }

            // Step 4: 追问建议 — 基于真实数据 + 洞察 + 关联维度
            try {
                progress.accept("正在生成追问建议...");
                String insight = chart != null ? chart.getInsight() : null;
                suggestedQuestions = suggestQuestionService.suggest(
                        userQuestion, generateResult.getSql(), rows, insight, session.getDataSourceId(), llmConfig);
                emitter.accept(new StreamEvent("suggest_questions", suggestedQuestions));
            } catch (Exception e) {
                log.error("Failed to generate suggested questions", e);
            }
        }
        progress.accept("回答已整理完成。");

        ChatMessage assistantMsg = buildAssistantMessage(
                sessionId,
                userQuestion,
                generateResult,
                executeResult,
                llmConfig,
                suggestedQuestions,
                thinkingContent.toString());
        if (chart != null) {
            assistantMsg.setAnalysis(chart.getInsight());
            assistantMsg.setChartType(chart.getChartType());
            assistantMsg.setChartOption(chart.getChartOption());
        }
        messageRepository.save(assistantMsg);
        updateSessionTitle(session, userQuestion);

        if (needClarification) {
            Map<String, Object> clarifyPayload = new java.util.HashMap<>();
            clarifyPayload.put("question", generateResult.getExplanation());
            clarifyPayload.put("options", generateResult.getClarifyOptions());
            emitter.accept(new StreamEvent("clarification", clarifyPayload));
        }
        emitter.accept(new StreamEvent("complete", assistantMsg));
    }

    @Transactional
    public ChatMessage analyzeMessage(Long messageId) {
        ChatMessage message = messageRepository.findById(messageId)
                .orElseThrow(() -> new RuntimeException("Message not found: " + messageId));

        if (message.getSqlResult() == null) {
            throw new RuntimeException("No data to analyze");
        }

        ChatSession session = sessionRepository.findById(message.getSessionId()).orElse(null);
        LlmConfig llmConfig = resolveLlmConfig(session);

        String userQuestion = findUserQuestion(message);

        try {
            SqlExecuteService.SqlExecuteResult executeResult = objectMapper.readValue(message.getSqlResult(),
                    SqlExecuteService.SqlExecuteResult.class);
            List<java.util.Map<String, Object>> rows = executeResult.getRows();
            if (rows == null || rows.isEmpty()) throw new RuntimeException("No data in results");

            ChartAgentService.ChartResult chart = chartAgentService.generate(
                    userQuestion, message.getSqlQuery(), rows, llmConfig);

            message.setAnalysis(chart.getInsight());
            message.setChartType(chart.getChartType());
            message.setChartOption(chart.getChartOption());
            return messageRepository.save(message);
        } catch (Exception e) {
            log.error("Analysis failed", e);
            throw new RuntimeException("Analysis failed: " + e.getMessage());
        }
    }

    public List<ChatMessage> getMessages(String sessionId) {
        return messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
    }

    public List<ChatSession> getSessions() {
        return sessionRepository.findAllByOrderByCreatedAtDesc();
    }

    @Transactional
    public void deleteSession(String sessionId) {
        messageRepository.deleteBySessionId(sessionId);
        sessionRepository.deleteById(sessionId);
    }

    // ===== Private helpers =====

    private LlmConfig resolveLlmConfig(ChatSession session) {
        if (session == null || session.getLlmConfigId() == null) {
            return llmConfigRepository.findByIsDefaultTrue().orElse(null);
        }
        return llmConfigRepository.findById(session.getLlmConfigId()).orElse(null);
    }

    private String applyPermission(String sql, Long dataSourceId, LlmConfig llmConfig) {
        String permissionRule = resolvePermissionRule();
        if (permissionRule != null && !permissionRule.isBlank()) {
            try {
                String engineName = dataSourceRepository.findById(dataSourceId).map(ds -> ds.getDialect().getDisplayName()).orElse("PostgreSQL");
                String result = sqlPermissionService.applyPermission(sql, engineName, permissionRule, llmConfig);
                log.info("SQL after permission applied: {}", result);
                return result;
            } catch (Exception e) {
                log.error("Permission application failed, falling back to original SQL", e);
            }
        }
        return sql;
    }

    private ChatMessage buildAssistantMessage(String sessionId, String userQuestion,
            SqlGenerateService.SqlGenerateResult generateResult,
            SqlExecuteService.SqlExecuteResult executeResult,
            LlmConfig llmConfig) {
        return buildAssistantMessage(sessionId, userQuestion, generateResult, executeResult, llmConfig, null, null);
    }

    private ChatMessage buildAssistantMessage(String sessionId, String userQuestion,
            SqlGenerateService.SqlGenerateResult generateResult,
            SqlExecuteService.SqlExecuteResult executeResult,
            LlmConfig llmConfig,
            List<String> precomputedSuggestedQuestions,
            String thinkingContent) {

        if (generateResult == null || !generateResult.isSuccess() || generateResult.getSql() == null) {
            String clarifyJson = null;
            if (generateResult != null && generateResult.isNeedClarification()
                    && generateResult.getClarifyOptions() != null && !generateResult.getClarifyOptions().isEmpty()) {
                try {
                    clarifyJson = objectMapper.writeValueAsString(generateResult.getClarifyOptions());
                } catch (Exception e) {
                    log.error("Failed to serialize clarify options", e);
                }
            }
            return ChatMessage.builder()
                    .sessionId(sessionId)
                    .role("assistant")
                    .content(generateResult != null ? generateResult.getExplanation() : "Unable to generate SQL, please check your question.")
                    .clarifyOptions(clarifyJson)
                    .thinkingContent(blankToNull(thinkingContent))
                    .build();
        }

        if (executeResult != null && executeResult.isSuccess()) {
            String resultJson = "{}";
            String suggestQuestionsJson = null;
            try {
                resultJson = objectMapper.writeValueAsString(executeResult);
            } catch (Exception e) { resultJson = "{}"; }

            if (precomputedSuggestedQuestions != null) {
                if (!precomputedSuggestedQuestions.isEmpty()) {
                    try {
                        suggestQuestionsJson = objectMapper.writeValueAsString(precomputedSuggestedQuestions);
                    } catch (Exception e) {
                        log.error("Failed to serialize suggested questions", e);
                    }
                }
            } else {
                try {
                    List<String> questions = suggestQuestionService.suggest(userQuestion, generateResult.getSql(), llmConfig);
                    suggestQuestionsJson = objectMapper.writeValueAsString(questions);
                } catch (Exception e) {
                    log.error("Failed to generate suggested questions", e);
                }
            }

            return ChatMessage.builder()
                    .sessionId(sessionId)
                    .role("assistant")
                    .content(generateResult.getExplanation())
                    .sqlQuery(generateResult.getSql())
                    .sqlResult(resultJson)
                    .suggestQuestions(suggestQuestionsJson)
                    .thinkingContent(blankToNull(thinkingContent))
                    .build();
        }

        String errorMsg = (executeResult != null) ? executeResult.getErrorMessage() : "Unknown error";
        return ChatMessage.builder()
                .sessionId(sessionId)
                .role("assistant")
                .content("SQL execution failed: " + errorMsg + "\n\nGenerated SQL:\n```sql\n" + generateResult.getSql() + "\n```")
                .sqlQuery(generateResult.getSql())
                .errorMsg(errorMsg)
                .thinkingContent(blankToNull(thinkingContent))
                .build();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private void emitProgress(Consumer<StreamEvent> emitter, String message) {
        // Status only — never emit canned progress as a "thinking" event, so the thinking
        // block shows the model's real reasoning_content exclusively.
        emitter.accept(new StreamEvent("status", Map.of("message", message)));
    }

    private void updateSessionTitle(ChatSession session, String userQuestion) {
        if ("New Chat".equals(session.getTitle()) && userQuestion.length() > 0) {
            session.setTitle(userQuestion.length() > 30 ? userQuestion.substring(0, 30) + "..." : userQuestion);
            sessionRepository.save(session);
        }
    }

    private String findUserQuestion(ChatMessage message) {
        List<ChatMessage> history = messageRepository.findBySessionIdOrderByCreatedAtAsc(message.getSessionId());
        int currentIndex = -1;
        for (int i = 0; i < history.size(); i++) {
            if (history.get(i).getId().equals(message.getId())) { currentIndex = i; break; }
        }
        if (currentIndex > 0) {
            for (int i = currentIndex - 1; i >= 0; i--) {
                if ("user".equals(history.get(i).getRole())) return history.get(i).getContent();
            }
        }
        return "";
    }

    /**
     * Builds a mutable conversation window for multi-turn SQL generation.
     * Returns the most recent {@code CONVERSATION_WINDOW} messages (including the
     * current user message just saved to DB) as a modifiable list so that the
     * retry loop can safely append in-memory self-healing turns.
     */
    private static final int CONVERSATION_WINDOW = 6;

    private List<ChatMessage> buildConversation(String sessionId) {
        List<ChatMessage> all = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        int start = Math.max(0, all.size() - CONVERSATION_WINDOW);
        return new java.util.ArrayList<>(all.subList(start, all.size()));
    }

    private String resolvePermissionRule() {
        return SecurityContext.getPermissionRule();
    }

    /**
     * 获取数据源中所有可用的表名（用于校验 LLM 返回的 tables 字段）。
     * 从数据库 JDBC metadata 获取。
     */
    private Set<String> fetchAvailableTables(Long dataSourceId) {
        DataSource ds = dataSourceRepository.findById(dataSourceId).orElse(null);
        if (ds == null) return Set.of();
        Set<String> tables = new LinkedHashSet<>();
        try (Connection conn = DriverManager.getConnection(ds.buildJdbcUrl(), ds.getUsername(), ds.getPassword())) {
            DatabaseMetaData meta = conn.getMetaData();
            ResultSet rs = meta.getTables(ds.getDbName(), null, "%", new String[]{"TABLE"});
            while (rs.next()) {
                tables.add(rs.getString("TABLE_NAME"));
            }
        } catch (Exception e) {
            log.warn("Failed to fetch available tables: {}", e.getMessage());
        }
        return tables;
    }
}
