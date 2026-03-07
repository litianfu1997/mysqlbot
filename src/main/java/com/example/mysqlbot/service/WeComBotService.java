package com.example.mysqlbot.service;

import com.example.mysqlbot.model.SystemConfig;
import com.example.mysqlbot.repository.SystemConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.chanjar.weixin.cp.api.WxCpService;
import me.chanjar.weixin.cp.api.impl.WxCpServiceImpl;
import me.chanjar.weixin.cp.bean.message.WxCpMessage;
import me.chanjar.weixin.cp.config.impl.WxCpDefaultConfigImpl;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

/**
 * 企业微信机器人服务
 * 负责企业微信 SDK 管理和消息收发
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WeComBotService {

    private final SystemConfigRepository configRepository;
    private final IMBotService imBotService;

    private volatile WxCpService wxCpService;
    private volatile WxCpDefaultConfigImpl wxCpConfig;
    private volatile boolean enabled = false;
    private volatile Integer agentId;

    // 配置键
    private static final String KEY_ENABLED = "wecom.enabled";
    private static final String KEY_CORP_ID = "wecom.corp_id";
    private static final String KEY_AGENT_ID = "wecom.agent_id";
    private static final String KEY_SECRET = "wecom.secret";
    private static final String KEY_TOKEN = "wecom.token";
    private static final String KEY_AES_KEY = "wecom.encoding_aes_key";

    @PostConstruct
    public void init() {
        refreshConfig();
    }

    /**
     * 从数据库加载配置，刷新 WxCpService 实例
     */
    public synchronized void refreshConfig() {
        try {
            String enabledStr = getConfigValue(KEY_ENABLED);
            if (!"true".equalsIgnoreCase(enabledStr)) {
                this.enabled = false;
                this.wxCpService = null;
                this.wxCpConfig = null;
                log.info("企业微信集成已禁用");
                return;
            }

            String corpId = getConfigValue(KEY_CORP_ID);
            String agentIdStr = getConfigValue(KEY_AGENT_ID);
            String secret = getConfigValue(KEY_SECRET);
            String token = getConfigValue(KEY_TOKEN);
            String aesKey = getConfigValue(KEY_AES_KEY);

            if (corpId == null || secret == null || token == null || aesKey == null || agentIdStr == null) {
                log.warn("企业微信配置不完整，跳过初始化");
                this.enabled = false;
                return;
            }

            this.agentId = Integer.parseInt(agentIdStr);

            WxCpDefaultConfigImpl config = new WxCpDefaultConfigImpl();
            config.setCorpId(corpId);
            config.setAgentId(this.agentId);
            config.setCorpSecret(secret);
            config.setToken(token);
            config.setAesKey(aesKey);

            WxCpServiceImpl service = new WxCpServiceImpl();
            service.setWxCpConfigStorage(config);

            this.wxCpService = service;
            this.wxCpConfig = config;
            this.enabled = true;

            log.info("企业微信集成已启用: corpId={}, agentId={}", corpId, this.agentId);
        } catch (Exception e) {
            log.error("初始化企业微信服务失败", e);
            this.enabled = false;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public WxCpService getWxCpService() {
        return wxCpService;
    }

    public WxCpDefaultConfigImpl getWxCpConfig() {
        return wxCpConfig;
    }

    public Integer getAgentId() {
        return agentId;
    }

    /**
     * 处理接收到的消息（由 Controller 调用）
     */
    public void handleMessage(String userId, String content) {
        if (!enabled || wxCpService == null) {
            log.warn("企业微信未启用，忽略消息");
            return;
        }

        if (content == null || content.isBlank()) {
            log.debug("忽略空消息: userId={}", userId);
            return;
        }

        log.info("收到企业微信消息: userId={}, content={}", userId, content);

        // 异步处理，通过 IMBotService 统一逻辑
        imBotService.processMessage("wecom", userId, content, this::sendTextMessage);
    }

    /**
     * 主动发送文本消息给用户
     */
    public void sendTextMessage(String userId, String text) {
        if (!enabled || wxCpService == null) {
            log.warn("企业微信未启用，无法发送消息");
            return;
        }

        try {
            // 如果文本过长，截断（企业微信文本消息限制 2048 字节）
            if (text.length() > 2000) {
                text = text.substring(0, 2000) + "\n\n... (结果过长，已截断)";
            }

            WxCpMessage message = WxCpMessage.TEXT()
                    .agentId(this.agentId)
                    .toUser(userId)
                    .content(text)
                    .build();

            wxCpService.getMessageService().send(message);
            log.info("企业微信消息发送成功: userId={}", userId);
        } catch (Exception e) {
            log.error("企业微信消息发送失败: userId={}", userId, e);
        }
    }

    private String getConfigValue(String key) {
        return configRepository.findById(key)
                .map(SystemConfig::getConfigValue)
                .orElse(null);
    }
}
