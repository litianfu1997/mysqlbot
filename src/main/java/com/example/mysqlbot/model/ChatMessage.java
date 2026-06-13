package com.example.mysqlbot.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "chat_message")
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, length = 36)
    private String sessionId;

    /**
     * 角色: user / assistant
     */
    @Column(nullable = false, length = 20)
    private String role;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "sql_query", columnDefinition = "TEXT")
    private String sqlQuery;

    @Column(name = "sql_result", columnDefinition = "TEXT")
    private String sqlResult;

    @Column(name = "error_msg", columnDefinition = "TEXT")
    private String errorMsg;

    @Column(name = "suggest_questions", columnDefinition = "TEXT")
    private String suggestQuestions;

    @Column(name = "thinking_content", columnDefinition = "TEXT")
    private String thinkingContent;

    @Column(name = "analysis", columnDefinition = "TEXT")
    private String analysis;

    @Column(name = "chart_type", length = 50)
    private String chartType;

    @Column(name = "x_axis", length = 100)
    private String xAxis;

    @Column(name = "y_axis", length = 100)
    private String yAxis;

    /**
     * 制图 Agent 直出的完整 ECharts option（JSON）。前端注入 dataset.source 后渲染。
     */
    @Column(name = "chart_option", columnDefinition = "TEXT")
    private String chartOption;

    /**
     * 主动澄清的可选项（JSON 数组）。非空时该消息为一条澄清请求。
     */
    @Column(name = "clarify_options", columnDefinition = "TEXT")
    private String clarifyOptions;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
