package com.example.mysqlbot.service;

import com.example.mysqlbot.model.ChatMessage;
import com.example.mysqlbot.model.ChatSession;
import com.example.mysqlbot.repository.ChatMessageRepository;
import com.example.mysqlbot.repository.ChatSessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 对话管理服务
 * 处理多轮对话、会话管理、消息存储
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
    private final ObjectMapper objectMapper;

    /**
     * 创建新会话
     */
    @Transactional
    public ChatSession createSession(Long dataSourceId, String title) {
        ChatSession session = ChatSession.builder()
                .title(title != null ? title : "新对话")
                .dataSourceId(dataSourceId)
                .build();
        return sessionRepository.save(session);
    }

    /**
     * 处理用户消息（核心流程）
     */
    @Transactional
    public ChatMessage chat(String sessionId, String userQuestion) {
        // 1. 获取会话
        ChatSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("会话不存在: " + sessionId));

        // 2. 保存用户消息
        ChatMessage userMsg = ChatMessage.builder()
                .sessionId(sessionId)
                .role("user")
                .content(userQuestion)
                .build();
        messageRepository.save(userMsg);

        // 3. 构建对话历史（最近 6 条）
        String chatHistory = buildChatHistory(sessionId);

        // 4. 生成 SQL (支持重试)
        int maxRetries = 3; // 默认重试 3 次
        String currentHistory = chatHistory;
        SqlGenerateService.SqlGenerateResult generateResult = null;
        SqlExecuteService.SqlExecuteResult executeResult = null;
        String lastErrorMsg = null;

        for (int i = 0; i < maxRetries; i++) {
            if (i > 0) {
                log.info("SQL 执行失败，进行第 {} 次重试，错误信息: {}", i, lastErrorMsg);
                // 将错误信息追加到对话历史中，引导 LLM 修正
                currentHistory = chatHistory + "\n\n[System Error]: 上一次生成的 SQL 执行失败，错误信息：" + lastErrorMsg
                        + "\n请根据错误信息修正 SQL。";
            }

            generateResult = sqlGenerateService.generate(userQuestion, session.getDataSourceId(), currentHistory);

            if (!generateResult.isSuccess() || generateResult.getSql() == null) {
                // 无法生成 SQL，直接跳出
                break;
            }

            // 5. 应用行级权限控制
            String permissionRule = resolvePermissionRule();
            String finalSql = generateResult.getSql();

            if (permissionRule != null && !permissionRule.isBlank()) {
                try {
                    finalSql = sqlPermissionService.applyPermission(finalSql, "MySQL", permissionRule);
                    log.info("应用权限后的 SQL: {}", finalSql);
                } catch (Exception e) {
                    log.error("权限应用失败，回退到原始 SQL", e);
                }
            }

            // 6. 执行 SQL
            executeResult = sqlExecuteService.execute(finalSql, session.getDataSourceId());

            if (executeResult.isSuccess()) {
                // 执行成功，跳出循环
                break;
            } else {
                // 执行失败，记录错误，继续重试
                lastErrorMsg = executeResult.getErrorMessage();
            }
        }

        ChatMessage assistantMsg;

        if (generateResult == null || !generateResult.isSuccess() || generateResult.getSql() == null) {
            // 无法生成 SQL
            assistantMsg = ChatMessage.builder()
                    .sessionId(sessionId)
                    .role("assistant")
                    .content(generateResult != null ? generateResult.getExplanation() : "无法生成 SQL，请检查问题描述。")
                    .build();
        } else if (executeResult != null && executeResult.isSuccess()) {
            // ... (Success handling code mostly same as before) ...
            String resultJson = null;
            String content = generateResult.getExplanation();
            String analysisResultText = null;
            String chartType = null;
            String xAxis = null;
            String yAxis = null;
            String suggestQuestionsJson = null;

            try {
                resultJson = objectMapper.writeValueAsString(executeResult);
            } catch (Exception e) {
                resultJson = "{}";
            }

            // 6. 执行数据分析与图表推荐
            try {
                DataAnalysisService.AnalysisResult analysis = dataAnalysisService.analyze(
                        userQuestion,
                        generateResult.getSql(),
                        executeResult.getRows());
                analysisResultText = analysis.getInsight();
                chartType = analysis.getChartType();
                xAxis = analysis.getXAxis();
                yAxis = analysis.getYAxis();
            } catch (Exception e) {
                log.error("数据分析失败", e);
            }

            // 7. 生成推荐问题 (Phase 2)
            try {
                List<String> questions = suggestQuestionService.suggest(userQuestion, generateResult.getSql());
                suggestQuestionsJson = objectMapper.writeValueAsString(questions);
            } catch (Exception e) {
                log.error("生成推荐问题失败", e);
            }

            assistantMsg = ChatMessage.builder()
                    .sessionId(sessionId)
                    .role("assistant")
                    .content(content)
                    .sqlQuery(generateResult.getSql())
                    .sqlResult(resultJson)
                    .analysis(analysisResultText)
                    .chartType(chartType)
                    .xAxis(xAxis)
                    .yAxis(yAxis)
                    .suggestQuestions(suggestQuestionsJson)
                    .build();

        } else {
            // 最终执行失败
            String errorMsg = (executeResult != null) ? executeResult.getErrorMessage() : "未知错误";
            String content = "SQL 执行失败：" + errorMsg + "\n\n生成的 SQL：\n```sql\n" + generateResult.getSql() + "\n```";

            assistantMsg = ChatMessage.builder()
                    .sessionId(sessionId)
                    .role("assistant")
                    .content(content)
                    .sqlQuery(generateResult.getSql())
                    .errorMsg(errorMsg)
                    .build();
        }

        // 7. 保存 assistant 消息
        messageRepository.save(assistantMsg);

        // 7. 更新会话标题（首次对话时用问题作为标题）
        if ("新对话".equals(session.getTitle()) && userQuestion.length() > 0) {
            session.setTitle(userQuestion.length() > 30 ? userQuestion.substring(0, 30) + "..." : userQuestion);
            sessionRepository.save(session);
        }

        return assistantMsg;
    }

    /**
     * 获取会话消息列表
     */
    public List<ChatMessage> getMessages(String sessionId) {
        return messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
    }

    /**
     * 获取所有会话列表
     */
    public List<ChatSession> getSessions() {
        return sessionRepository.findAllByOrderByCreatedAtDesc();
    }

    /**
     * 删除会话
     */
    @Transactional
    public void deleteSession(String sessionId) {
        messageRepository.deleteBySessionId(sessionId);
        sessionRepository.deleteById(sessionId);
    }

    /**
     * 构建对话历史字符串（用于多轮对话上下文）
     */
    private String buildChatHistory(String sessionId) {
        List<ChatMessage> messages = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        if (messages.isEmpty()) {
            return "（无历史对话）";
        }

        // 取最近 6 条消息
        int start = Math.max(0, messages.size() - 6);
        return messages.subList(start, messages.size()).stream()
                .map(m -> (m.getRole().equals("user") ? "用户: " : "助手: ") + m.getContent())
                .collect(Collectors.joining("\n"));
    }

    /**
     * 解析当前用户的行级权限规则
     * 当集成 Spring Security 后，可在此从 SecurityContext 获取用户角色并返回对应的过滤条件
     * 例如: "dept_id = 1001" 或 "tenant_id = 'abc'"
     * 当前返回 null 表示不应用任何过滤（超级管理员模式）
     */
    private String resolvePermissionRule() {
        // TODO: 集成 Spring Security 后在此实现
        // Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        // if (auth != null && auth.getAuthorities().stream().noneMatch(a ->
        // a.getAuthority().equals("ROLE_ADMIN"))) {
        // return "dept_id = " + getUserDeptId(auth);
        // }
        return null;
    }
}
