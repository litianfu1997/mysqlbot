package com.example.mysqlbot.service;

import com.example.mysqlbot.model.ChatMessage;
import com.example.mysqlbot.model.ChatSession;
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

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Optional;
import com.example.mysqlbot.security.SecurityContext;
import java.util.function.Consumer;
import java.util.stream.Collectors;

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
    private final DataAnalysisService dataAnalysisService;
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

        String chatHistory = buildChatHistory(sessionId);

        // SQL generation with retry
        SqlGenerateService.SqlGenerateResult generateResult = null;
        SqlExecuteService.SqlExecuteResult executeResult = null;
        String lastErrorMsg = null;

        for (int i = 0; i < 3; i++) {
            String currentHistory = chatHistory;
            if (i > 0) {
                log.info("SQL execution failed, retry #{}: {}", i, lastErrorMsg);
                currentHistory = chatHistory + "\n\n[System Error]: Previous SQL failed: " + lastErrorMsg
                        + "\nPlease fix the SQL based on the error.";
            }

            generateResult = sqlGenerateService.generate(userQuestion, session.getDataSourceId(), currentHistory, llmConfig);
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
     */
    @Transactional
    public void chatStream(String sessionId, String userQuestion, Consumer<StreamEvent> emitter) {
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

        String chatHistory = buildChatHistory(sessionId);

        // Step 1: Generate SQL
        emitter.accept(new StreamEvent("status", java.util.Map.of("message", "Generating SQL...")));

        SqlGenerateService.SqlGenerateResult generateResult = null;
        SqlExecuteService.SqlExecuteResult executeResult = null;
        String lastErrorMsg = null;

        for (int i = 0; i < 3; i++) {
            String currentHistory = chatHistory;
            if (i > 0) {
                log.info("SQL execution failed, retry #{}: {}", i, lastErrorMsg);
                currentHistory = chatHistory + "\n\n[System Error]: Previous SQL failed: " + lastErrorMsg
                        + "\nPlease fix the SQL based on the error.";
                emitter.accept(new StreamEvent("status", java.util.Map.of("message", "Retrying SQL generation (attempt " + (i + 1) + ")...")));
            }

            generateResult = sqlGenerateService.generate(userQuestion, session.getDataSourceId(), currentHistory, llmConfig);
            if (!generateResult.isSuccess() || generateResult.getSql() == null) break;

            emitter.accept(new StreamEvent("sql_generated", java.util.Map.of(
                    "sql", generateResult.getSql(),
                    "explanation", generateResult.getExplanation() != null ? generateResult.getExplanation() : ""
            )));

            String finalSql = applyPermission(generateResult.getSql(), session.getDataSourceId(), llmConfig);

            // Step 2: Execute SQL
            emitter.accept(new StreamEvent("status", java.util.Map.of("message", "Executing SQL...")));
            executeResult = sqlExecuteService.execute(finalSql, session.getDataSourceId());
            if (executeResult.isSuccess()) break;
            lastErrorMsg = executeResult.getErrorMessage();
        }

        if (generateResult != null && generateResult.isSuccess() && executeResult != null && executeResult.isSuccess()) {
            emitter.accept(new StreamEvent("sql_executed", executeResult));

            // Step 3: Generate suggested questions
            try {
                List<String> questions = suggestQuestionService.suggest(userQuestion, generateResult.getSql(), llmConfig);
                emitter.accept(new StreamEvent("suggest_questions", questions));
            } catch (Exception e) {
                log.error("Failed to generate suggested questions", e);
            }
        }

        ChatMessage assistantMsg = buildAssistantMessage(sessionId, userQuestion, generateResult, executeResult, llmConfig);
        messageRepository.save(assistantMsg);
        updateSessionTitle(session, userQuestion);

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

            DataAnalysisService.AnalysisResult analysis = dataAnalysisService.analyze(
                    userQuestion, message.getSqlQuery(), rows, llmConfig);

            message.setAnalysis(analysis.getInsight());
            message.setChartType(analysis.getChartType());
            message.setXAxis(analysis.getXAxis());
            message.setYAxis(analysis.getYAxis());
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

    private LlmConfig resolveLlmConfigById(Long llmConfigId) {
        if (llmConfigId == null) return null;
        return llmConfigRepository.findById(llmConfigId).orElse(null);
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

        if (generateResult == null || !generateResult.isSuccess() || generateResult.getSql() == null) {
            return ChatMessage.builder()
                    .sessionId(sessionId)
                    .role("assistant")
                    .content(generateResult != null ? generateResult.getExplanation() : "Unable to generate SQL, please check your question.")
                    .build();
        }

        if (executeResult != null && executeResult.isSuccess()) {
            String resultJson = "{}";
            String suggestQuestionsJson = null;
            try {
                resultJson = objectMapper.writeValueAsString(executeResult);
            } catch (Exception e) { resultJson = "{}"; }

            try {
                List<String> questions = suggestQuestionService.suggest(userQuestion, generateResult.getSql(), llmConfig);
                suggestQuestionsJson = objectMapper.writeValueAsString(questions);
            } catch (Exception e) {
                log.error("Failed to generate suggested questions", e);
            }

            return ChatMessage.builder()
                    .sessionId(sessionId)
                    .role("assistant")
                    .content(generateResult.getExplanation())
                    .sqlQuery(generateResult.getSql())
                    .sqlResult(resultJson)
                    .suggestQuestions(suggestQuestionsJson)
                    .build();
        }

        String errorMsg = (executeResult != null) ? executeResult.getErrorMessage() : "Unknown error";
        return ChatMessage.builder()
                .sessionId(sessionId)
                .role("assistant")
                .content("SQL execution failed: " + errorMsg + "\n\nGenerated SQL:\n```sql\n" + generateResult.getSql() + "\n```")
                .sqlQuery(generateResult.getSql())
                .errorMsg(errorMsg)
                .build();
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

    /** Build structured message list for multi-turn LLM chat. */
    private List<java.util.Map<String, String>> buildChatMessages(String sessionId) {
        List<ChatMessage> msgs = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        int start = Math.max(0, msgs.size() - 6);
        List<java.util.Map<String, String>> result = new ArrayList<>();
        for (ChatMessage m : msgs.subList(start, msgs.size())) {
            java.util.Map<String, String> msg = new LinkedHashMap<>();
            msg.put("role", m.getRole().equals("user") ? "user" : "assistant");
            msg.put("content", m.getContent());
            result.add(msg);
        }
        return result;
    }

    private String buildChatHistory(String sessionId) {
        List<ChatMessage> messages = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        if (messages.isEmpty()) return "(no chat history)";
        int start = Math.max(0, messages.size() - 6);
        return messages.subList(start, messages.size()).stream()
                .map(m -> (m.getRole().equals("user") ? "User: " : "Assistant: ") + m.getContent())
                .collect(Collectors.joining("\n"));
    }

    private String resolvePermissionRule() {
        return SecurityContext.getPermissionRule();
    }
}


