package com.example.mysqlbot.controller;

import com.example.mysqlbot.service.FeishuBotService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 飞书回调控制器
 * 处理 URL 验证和消息事件接收
 */
@Slf4j
@RestController
@RequestMapping("/feishu/callback")
@RequiredArgsConstructor
public class FeishuCallbackController {

    private final FeishuBotService feishuBotService;
    private final ObjectMapper objectMapper;

    /**
     * 飞书事件回调（POST）
     * URL 验证和消息接收共用同一个 POST 接口，通过 type 字段区分
     */
    @PostMapping
    public ResponseEntity<?> callback(@RequestBody String requestBody) {

        if (!feishuBotService.isEnabled()) {
            log.warn("飞书未启用，忽略回调");
            return ResponseEntity.ok(Map.of());
        }

        try {
            // 尝试解密（如果配置了 encrypt_key）
            JsonNode rootNode = objectMapper.readTree(requestBody);
            String eventBody = requestBody;

            // 如果是加密事件
            if (rootNode.has("encrypt")) {
                String encrypt = rootNode.get("encrypt").asText();
                eventBody = feishuBotService.decryptEvent(encrypt);
                rootNode = objectMapper.readTree(eventBody);
            }

            // URL 验证请求
            if (rootNode.has("type") && "url_verification".equals(rootNode.get("type").asText())) {
                String challenge = rootNode.get("challenge").asText();
                log.info("飞书 URL 验证成功");
                return ResponseEntity.ok(Map.of("challenge", challenge));
            }

            // v2.0 事件格式
            if (rootNode.has("header")) {
                JsonNode header = rootNode.get("header");
                String eventType = header.has("event_type") ? header.get("event_type").asText() : "";

                // 消息接收事件
                if ("im.message.receive_v1".equals(eventType)) {
                    handleMessageEvent(rootNode);
                }
            }

            return ResponseEntity.ok(Map.of());
        } catch (Exception e) {
            log.error("处理飞书回调失败", e);
            return ResponseEntity.ok(Map.of());
        }
    }

    /**
     * 处理消息接收事件
     */
    private void handleMessageEvent(JsonNode rootNode) {
        try {
            JsonNode event = rootNode.get("event");
            if (event == null) return;

            JsonNode sender = event.get("sender");
            JsonNode message = event.get("message");

            if (sender == null || message == null) return;

            // 获取发送者 open_id
            JsonNode senderId = sender.get("sender_id");
            if (senderId == null) return;
            String openId = senderId.has("open_id") ? senderId.get("open_id").asText() : null;
            if (openId == null) return;

            // 只处理文本消息
            String msgType = message.has("message_type") ? message.get("message_type").asText() : "";
            if (!"text".equals(msgType)) {
                log.debug("忽略非文本消息: type={}", msgType);
                return;
            }

            // 提取文本内容
            String contentJson = message.has("content") ? message.get("content").asText() : "{}";
            JsonNode contentNode = objectMapper.readTree(contentJson);
            String text = contentNode.has("text") ? contentNode.get("text").asText() : "";

            if (text.isBlank()) return;

            // 异步处理消息
            feishuBotService.handleMessage(openId, text);

        } catch (Exception e) {
            log.error("解析飞书消息事件失败", e);
        }
    }
}
