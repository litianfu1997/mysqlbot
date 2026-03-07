package com.example.mysqlbot.service;

import com.example.mysqlbot.model.SystemConfig;
import com.example.mysqlbot.repository.SystemConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.oapi.Client;
import com.lark.oapi.service.im.v1.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;

/**
 * 飞书机器人服务
 * 负责飞书 SDK 管理和消息收发
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeishuBotService {

    private final SystemConfigRepository configRepository;
    private final IMBotService imBotService;
    private final ObjectMapper objectMapper;

    private volatile Client feishuClient;
    private volatile boolean enabled = false;
    private volatile String verificationToken;
    private volatile String encryptKey;

    // 配置键
    private static final String KEY_ENABLED = "feishu.enabled";
    private static final String KEY_APP_ID = "feishu.app_id";
    private static final String KEY_APP_SECRET = "feishu.app_secret";
    private static final String KEY_VERIFICATION_TOKEN = "feishu.verification_token";
    private static final String KEY_ENCRYPT_KEY = "feishu.encrypt_key";

    @PostConstruct
    public void init() {
        refreshConfig();
    }

    /**
     * 从数据库加载配置，刷新飞书 Client
     */
    public synchronized void refreshConfig() {
        try {
            String enabledStr = getConfigValue(KEY_ENABLED);
            if (!"true".equalsIgnoreCase(enabledStr)) {
                this.enabled = false;
                this.feishuClient = null;
                log.info("飞书集成已禁用");
                return;
            }

            String appId = getConfigValue(KEY_APP_ID);
            String appSecret = getConfigValue(KEY_APP_SECRET);
            this.verificationToken = getConfigValue(KEY_VERIFICATION_TOKEN);
            this.encryptKey = getConfigValue(KEY_ENCRYPT_KEY);

            if (appId == null || appSecret == null) {
                log.warn("飞书配置不完整，跳过初始化");
                this.enabled = false;
                return;
            }

            this.feishuClient = Client.newBuilder(appId, appSecret).build();
            this.enabled = true;

            log.info("飞书集成已启用: appId={}", appId);
        } catch (Exception e) {
            log.error("初始化飞书服务失败", e);
            this.enabled = false;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getVerificationToken() {
        return verificationToken;
    }

    public String getEncryptKey() {
        return encryptKey;
    }

    /**
     * 解密飞书事件消息
     */
    public String decryptEvent(String encrypt) {
        if (encryptKey == null || encryptKey.isBlank()) {
            return encrypt;
        }
        try {
            byte[] keyBytes = generateKeyBytes(encryptKey);
            byte[] encryptedBytes = Base64.getDecoder().decode(encrypt);
            Cipher cipher = Cipher.getInstance("AES/CBC/NOPADDING");
            byte[] iv = Arrays.copyOfRange(encryptedBytes, 0, 16);
            byte[] encrypted = Arrays.copyOfRange(encryptedBytes, 16, encryptedBytes.length);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new IvParameterSpec(iv));
            byte[] decrypted = cipher.doFinal(encrypted);
            // Remove PKCS7 padding
            int padLen = decrypted[decrypted.length - 1];
            return new String(decrypted, 0, decrypted.length - padLen, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("飞书事件解密失败", e);
            throw new RuntimeException("飞书事件解密失败: " + e.getMessage());
        }
    }

    private byte[] generateKeyBytes(String key) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return Arrays.copyOfRange(digest.digest(key.getBytes(StandardCharsets.UTF_8)), 0, 32);
    }

    /**
     * 处理接收到的消息事件
     */
    public void handleMessage(String openId, String content) {
        if (!enabled || feishuClient == null) {
            log.warn("飞书未启用，忽略消息");
            return;
        }

        if (content == null || content.isBlank()) {
            log.debug("忽略空消息: openId={}", openId);
            return;
        }

        log.info("收到飞书消息: openId={}, content={}", openId, content);

        // 异步处理
        imBotService.processMessage("feishu", openId, content, this::sendTextMessage);
    }

    /**
     * 主动发送文本消息给用户
     */
    public void sendTextMessage(String openId, String text) {
        if (!enabled || feishuClient == null) {
            log.warn("飞书未启用，无法发送消息");
            return;
        }

        try {
            // 飞书文本消息限制较宽松，但仍做截断
            if (text.length() > 4000) {
                text = text.substring(0, 4000) + "\n\n... (结果过长，已截断)";
            }

            // 构建消息内容 JSON
            String contentJson = objectMapper.writeValueAsString(
                    java.util.Map.of("text", text));

            CreateMessageReq req = CreateMessageReq.newBuilder()
                    .receiveIdType("open_id")
                    .createMessageReqBody(CreateMessageReqBody.newBuilder()
                            .receiveId(openId)
                            .msgType("text")
                            .content(contentJson)
                            .build())
                    .build();

            CreateMessageResp resp = feishuClient.im().message().create(req);

            if (!resp.success()) {
                log.error("飞书消息发送失败: code={}, msg={}", resp.getCode(), resp.getMsg());
            } else {
                log.info("飞书消息发送成功: openId={}", openId);
            }
        } catch (Exception e) {
            log.error("飞书消息发送失败: openId={}", openId, e);
        }
    }

    private String getConfigValue(String key) {
        return configRepository.findById(key)
                .map(SystemConfig::getConfigValue)
                .orElse(null);
    }
}
