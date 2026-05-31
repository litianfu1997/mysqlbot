package com.example.mysqlbot.controller;

import com.example.mysqlbot.model.ChatMessage;
import com.example.mysqlbot.model.ChatSession;
import com.example.mysqlbot.service.ChatService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Chat API with SSE streaming support.
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ChatController {

    private final ChatService chatService;
    private final ObjectMapper objectMapper;

    private final ExecutorService sseExecutor = Executors.newVirtualThreadPerTaskExecutor();

    @GetMapping("/sessions")
    public List<ChatSession> getSessions() {
        return chatService.getSessions();
    }

    @PostMapping("/sessions")
    public ChatSession createSession(@RequestBody CreateSessionRequest request) {
        return chatService.createSession(request.getDataSourceId(), request.getTitle(), request.getLlmConfigId());
    }

    @GetMapping("/sessions/{sessionId}/messages")
    public List<ChatMessage> getMessages(@PathVariable("sessionId") String sessionId) {
        return chatService.getMessages(sessionId);
    }

    @PostMapping("/sessions/{sessionId}/messages")
    public ResponseEntity<ChatMessage> sendMessage(
            @PathVariable("sessionId") String sessionId,
            @RequestBody SendMessageRequest request) {
        try {
            ChatMessage response = chatService.chat(sessionId, request.getContent());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            ChatMessage errorMsg = ChatMessage.builder()
                    .sessionId(sessionId)
                    .role("assistant")
                    .content("Processing failed: [" + e.getClass().getSimpleName() + "] " + e.getMessage() + " at " + Thread.currentThread().getStackTrace()[2])
                    .errorMsg(e.getMessage())
                    .build();
            return ResponseEntity.ok(errorMsg);
        }
    }

    /**
     * SSE streaming endpoint.
     * All payloads are JSON encoded so token whitespace and line breaks are preserved.
     */
    @PostMapping(value = "/sessions/{sessionId}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sendMessageStream(
            @PathVariable("sessionId") String sessionId,
            @RequestBody SendMessageRequest request) {

        SseEmitter emitter = new SseEmitter(180_000L); // 3 min timeout for streaming

        sseExecutor.execute(() -> {
            try {
                chatService.chatStream(sessionId, request.getContent(), request.isThinking(), (event) -> {
                    try {
                        sendEvent(emitter, event.type(), event.data());
                        if ("complete".equals(event.type()) || "error".equals(event.type())) {
                            emitter.complete();
                        }
                    } catch (Exception e) {
                        log.error("SSE emit failed", e);
                        emitter.completeWithError(e);
                    }
                });
            } catch (Exception e) {
                log.error("SSE stream error", e);
                try {
                    sendEvent(emitter, "error",
                            java.util.Map.of("message", e.getMessage() != null ? e.getMessage() : "Unknown error"));
                } catch (Exception ignored) {}
                emitter.complete();
            }
        });

        emitter.onTimeout(() -> log.warn("SSE emitter timed out for session {}", sessionId));
        emitter.onError(e -> log.warn("SSE emitter error for session {}: {}", sessionId, e.getMessage()));
        return emitter;
    }

    private void sendEvent(SseEmitter emitter, String eventName, Object data) throws java.io.IOException {
        String json = objectMapper.writeValueAsString(data);
        emitter.send(SseEmitter.event()
                .name(eventName)
                .data(json, MediaType.APPLICATION_JSON));
    }

    @PostMapping("/messages/{messageId}/analyze")
    public ResponseEntity<ChatMessage> analyzeMessage(@PathVariable("messageId") Long messageId) {
        try {
            ChatMessage response = chatService.analyzeMessage(messageId);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            ChatMessage errorRes = ChatMessage.builder()
                    .id(messageId)
                    .errorMsg(e.getMessage())
                    .build();
            return ResponseEntity.ok(errorRes);
        }
    }

    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<Void> deleteSession(@PathVariable("sessionId") String sessionId) {
        chatService.deleteSession(sessionId);
        return ResponseEntity.ok().build();
    }

    @Data
    public static class CreateSessionRequest {
        private Long dataSourceId;
        private String title;
        private Long llmConfigId;
    }

    @Data
    public static class SendMessageRequest {
        private String content;
        /** 是否开启「深度思考」：true 时该次请求走推理模型并流式输出思考过程 */
        private boolean thinking;
    }
}
