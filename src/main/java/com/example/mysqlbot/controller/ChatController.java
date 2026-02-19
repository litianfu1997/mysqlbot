package com.example.mysqlbot.controller;

import com.example.mysqlbot.model.ChatMessage;
import com.example.mysqlbot.model.ChatSession;
import com.example.mysqlbot.service.ChatService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 对话 API
 */
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ChatController {

    private final ChatService chatService;

    /**
     * 获取所有会话列表
     */
    @GetMapping("/sessions")
    public List<ChatSession> getSessions() {
        return chatService.getSessions();
    }

    /**
     * 创建新会话
     */
    @PostMapping("/sessions")
    public ChatSession createSession(@RequestBody CreateSessionRequest request) {
        return chatService.createSession(request.getDataSourceId(), request.getTitle());
    }

    /**
     * 获取会话消息列表
     */
    @GetMapping("/sessions/{sessionId}/messages")
    public List<ChatMessage> getMessages(@PathVariable String sessionId) {
        return chatService.getMessages(sessionId);
    }

    /**
     * 发送消息（核心接口）
     */
    @PostMapping("/sessions/{sessionId}/messages")
    public ResponseEntity<ChatMessage> sendMessage(
            @PathVariable String sessionId,
            @RequestBody SendMessageRequest request) {
        try {
            ChatMessage response = chatService.chat(sessionId, request.getContent());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            ChatMessage errorMsg = ChatMessage.builder()
                    .sessionId(sessionId)
                    .role("assistant")
                    .content("处理失败：" + e.getMessage())
                    .errorMsg(e.getMessage())
                    .build();
            return ResponseEntity.ok(errorMsg);
        }
    }

    /**
     * 删除会话
     */
    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<Void> deleteSession(@PathVariable String sessionId) {
        chatService.deleteSession(sessionId);
        return ResponseEntity.ok().build();
    }

    @Data
    public static class CreateSessionRequest {
        private Long dataSourceId;
        private String title;
    }

    @Data
    public static class SendMessageRequest {
        private String content;
    }
}
