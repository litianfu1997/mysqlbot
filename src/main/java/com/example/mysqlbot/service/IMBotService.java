package com.example.mysqlbot.service;

import com.example.mysqlbot.model.ChatMessage;
import com.example.mysqlbot.model.ChatSession;
import com.example.mysqlbot.model.DataSource;
import com.example.mysqlbot.repository.DataSourceRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * IM 机器人通用服务
 * 封装企业微信/飞书共用的核心逻辑：会话管理、数据源选择、查询处理、结果格式化
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IMBotService {

    private final ChatService chatService;
    private final DataSourceRepository dataSourceRepository;
    private final ObjectMapper objectMapper;

    /**
     * 用户 → 会话ID 映射
     * key = "platform:userId"，例如 "wecom:zhangsan" 或 "feishu:ou_xxx"
     */
    private final ConcurrentHashMap<String, String> userSessionMap = new ConcurrentHashMap<>();

    /**
     * 用户 → 已选数据源ID 映射
     */
    private final ConcurrentHashMap<String, Long> userDataSourceMap = new ConcurrentHashMap<>();

    /**
     * 用户 → 等待选择数据源 标志
     */
    private final ConcurrentHashMap<String, Boolean> userWaitingForSelection = new ConcurrentHashMap<>();

    /**
     * 异步处理 IM 消息（核心入口）
     *
     * @param platform      平台标识 ("wecom" / "feishu")
     * @param userId        平台用户ID
     * @param content       消息内容
     * @param messageSender 消息发送回调 (userId, replyText) -> void
     */
    @Async("imBotExecutor")
    public void processMessage(String platform, String userId, String content,
                               BiConsumer<String, String> messageSender) {
        String userKey = platform + ":" + userId;
        try {
            String trimmed = content.trim();

            // 处理特殊指令
            if ("切换数据源".equals(trimmed) || "数据源".equals(trimmed)) {
                userSessionMap.remove(userKey);
                userDataSourceMap.remove(userKey);
                userWaitingForSelection.put(userKey, true);
                String dsListText = buildDataSourceList();
                messageSender.accept(userId, dsListText);
                return;
            }

            if ("帮助".equals(trimmed) || "help".equalsIgnoreCase(trimmed)) {
                messageSender.accept(userId, buildHelpText());
                return;
            }

            if ("新对话".equals(trimmed) || "重置".equals(trimmed)) {
                userSessionMap.remove(userKey);
                messageSender.accept(userId, "✅ 已开启新对话，请直接输入问题。");
                return;
            }

            // 如果用户正在选择数据源（回复了序号）
            if (Boolean.TRUE.equals(userWaitingForSelection.get(userKey))) {
                Long dsId = handleDataSourceSelection(trimmed);
                if (dsId != null) {
                    userDataSourceMap.put(userKey, dsId);
                    userWaitingForSelection.remove(userKey);
                    userSessionMap.remove(userKey); // 切换数据源时清除旧会话
                    DataSource ds = dataSourceRepository.findById(dsId).orElse(null);
                    String dsName = ds != null ? ds.getName() : String.valueOf(dsId);
                    messageSender.accept(userId, "✅ 已选择数据源：" + dsName + "\n\n请直接输入您的查询问题，例如：\n「本月销售额是多少？」");
                } else {
                    messageSender.accept(userId, "❌ 无效的选择，请输入数据源前面的序号。\n\n" + buildDataSourceList());
                }
                return;
            }

            // 如果还未选择数据源，先询问
            if (!userDataSourceMap.containsKey(userKey)) {
                // 检查是否只有一个数据源 → 自动选择
                List<DataSource> allDs = dataSourceRepository.findAll();
                if (allDs.isEmpty()) {
                    messageSender.accept(userId, "⚠️ 系统中尚未配置任何数据源，请联系管理员在后台添加数据源。");
                    return;
                }
                if (allDs.size() == 1) {
                    // 只有一个数据源，自动选择
                    userDataSourceMap.put(userKey, allDs.get(0).getId());
                } else {
                    // 多个数据源，让用户选择
                    userWaitingForSelection.put(userKey, true);
                    messageSender.accept(userId, "👋 你好！请先选择要查询的数据源：\n\n" + buildDataSourceList());
                    return;
                }
            }

            // 获取或创建会话
            String sessionId = getOrCreateSession(userKey);

            // 调用核心 ChatService 处理查询
            ChatMessage response = chatService.chat(sessionId, trimmed);

            // 格式化回复
            String replyText = formatReply(response);
            messageSender.accept(userId, replyText);

        } catch (Exception e) {
            log.error("处理 IM 消息失败: platform={}, userId={}", platform, userId, e);
            messageSender.accept(userId, "❌ 处理失败：" + e.getMessage() + "\n\n可以尝试重新描述问题，或发送「新对话」重置会话。");
        }
    }

    /**
     * 构建数据源列表文本
     */
    private String buildDataSourceList() {
        List<DataSource> list = dataSourceRepository.findAll();
        if (list.isEmpty()) {
            return "⚠️ 暂无可用数据源";
        }
        StringBuilder sb = new StringBuilder("📊 可用数据源列表：\n");
        for (int i = 0; i < list.size(); i++) {
            DataSource ds = list.get(i);
            sb.append(String.format("\n%d. %s", i + 1, ds.getName()));
            if (ds.getDescription() != null && !ds.getDescription().isBlank()) {
                sb.append(" - ").append(ds.getDescription());
            }
        }
        sb.append("\n\n请回复数字序号选择数据源。");
        return sb.toString();
    }

    /**
     * 处理数据源选择（输入序号）
     */
    private Long handleDataSourceSelection(String input) {
        try {
            int index = Integer.parseInt(input.trim()) - 1;
            List<DataSource> list = dataSourceRepository.findAll();
            if (index >= 0 && index < list.size()) {
                return list.get(index).getId();
            }
        } catch (NumberFormatException ignored) {
        }
        return null;
    }

    /**
     * 获取或创建用户的会话
     */
    private String getOrCreateSession(String userKey) {
        return userSessionMap.computeIfAbsent(userKey, k -> {
            Long dsId = userDataSourceMap.get(k);
            ChatSession session = chatService.createSession(dsId, "IM对话");
            return session.getId();
        });
    }

    /**
     * 格式化 ChatMessage 为纯文本回复
     */
    private String formatReply(ChatMessage msg) {
        StringBuilder sb = new StringBuilder();

        // 文字回复
        if (msg.getContent() != null && !msg.getContent().isBlank()) {
            sb.append(msg.getContent());
        }

        // 如果有错误
        if (msg.getErrorMsg() != null && !msg.getErrorMsg().isBlank()) {
            sb.append("\n\n⚠️ 错误：").append(msg.getErrorMsg());
        }

        // SQL
        if (msg.getSqlQuery() != null && !msg.getSqlQuery().isBlank()) {
            sb.append("\n\n📝 SQL：\n").append(msg.getSqlQuery());
        }

        // 数据结果（简化为文本表格）
        if (msg.getSqlResult() != null && !msg.getSqlResult().isBlank()
                && msg.getErrorMsg() == null) {
            try {
                String tableText = formatSqlResultAsTable(msg.getSqlResult());
                if (tableText != null && !tableText.isBlank()) {
                    sb.append("\n\n📊 查询结果：\n").append(tableText);
                }
            } catch (Exception e) {
                log.warn("格式化结果失败", e);
            }
        }

        // 推荐问题
        if (msg.getSuggestQuestions() != null && !msg.getSuggestQuestions().isBlank()) {
            try {
                List<String> questions = objectMapper.readValue(msg.getSuggestQuestions(),
                        new TypeReference<List<String>>() {});
                if (!questions.isEmpty()) {
                    sb.append("\n\n💡 你还可以问：");
                    for (String q : questions) {
                        sb.append("\n· ").append(q);
                    }
                }
            } catch (Exception ignored) {
            }
        }

        return sb.toString();
    }

    /**
     * 将 SQL 结果 JSON 格式化为纯文本表格
     */
    @SuppressWarnings("unchecked")
    private String formatSqlResultAsTable(String sqlResultJson) throws Exception {
        Map<String, Object> result = objectMapper.readValue(sqlResultJson,
                new TypeReference<Map<String, Object>>() {});

        Object rowsObj = result.get("rows");
        if (rowsObj == null) return null;

        List<Map<String, Object>> rows = (List<Map<String, Object>>) rowsObj;
        if (rows.isEmpty()) return "（无数据）";

        // 限制显示行数
        int maxDisplay = Math.min(rows.size(), 20);
        List<String> columns = rows.get(0).keySet().stream().toList();

        // 计算列宽（简化处理）
        StringBuilder sb = new StringBuilder();

        // 表头
        sb.append(String.join(" | ", columns)).append("\n");
        sb.append("-".repeat(columns.size() * 12)).append("\n");

        // 数据行
        for (int i = 0; i < maxDisplay; i++) {
            Map<String, Object> row = rows.get(i);
            StringBuilder line = new StringBuilder();
            for (int j = 0; j < columns.size(); j++) {
                if (j > 0) line.append(" | ");
                Object val = row.get(columns.get(j));
                line.append(val != null ? val.toString() : "NULL");
            }
            sb.append(line).append("\n");
        }

        if (rows.size() > maxDisplay) {
            sb.append(String.format("\n... 共 %d 条记录，仅显示前 %d 条", rows.size(), maxDisplay));
        } else {
            sb.append(String.format("\n共 %d 条记录", rows.size()));
        }

        return sb.toString();
    }

    /**
     * 构建帮助文本
     */
    private String buildHelpText() {
        return """
                🤖 MySqlBot IM 助手
                
                直接输入自然语言问题即可查询数据库，例如：
                · 本月销售额是多少？
                · 哪个部门业绩最好？
                · 最近一周新增了多少用户？
                
                📌 指令：
                · 切换数据源 — 切换查询的数据库
                · 新对话 — 清除对话历史，开始新会话
                · 帮助 — 显示此帮助信息""";
    }
}
