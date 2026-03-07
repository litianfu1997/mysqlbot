package com.example.mysqlbot.controller;

import com.example.mysqlbot.service.WeComBotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.chanjar.weixin.cp.config.impl.WxCpDefaultConfigImpl;
import me.chanjar.weixin.cp.util.crypto.WxCpCryptUtil;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/**
 * 企业微信回调控制器
 * 处理 URL 验证和消息接收
 */
@Slf4j
@RestController
@RequestMapping("/wecom/callback")
@RequiredArgsConstructor
public class WeComCallbackController {

    private final WeComBotService weComBotService;

    /**
     * URL 验证回调（GET）
     */
    @GetMapping(produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> verifyUrl(
            @RequestParam("msg_signature") String msgSignature,
            @RequestParam("timestamp") String timestamp,
            @RequestParam("nonce") String nonce,
            @RequestParam("echostr") String echostr) {

        log.info("收到企业微信 URL 验证请求");

        if (!weComBotService.isEnabled()) {
            log.warn("企业微信未启用");
            return ResponseEntity.badRequest().body("WeCom not enabled");
        }

        try {
            WxCpDefaultConfigImpl config = weComBotService.getWxCpConfig();
            WxCpCryptUtil cryptUtil = new WxCpCryptUtil(config);
            // 验证签名并解密 echostr
            String decrypted = cryptUtil.decrypt(msgSignature, timestamp, nonce, echostr);
            log.info("企业微信 URL 验证成功");
            return ResponseEntity.ok(decrypted);
        } catch (Exception e) {
            log.error("企业微信 URL 验证失败", e);
            return ResponseEntity.badRequest().body("Verification failed");
        }
    }

    /**
     * 接收消息回调（POST）
     */
    @PostMapping(produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> receiveMessage(
            @RequestParam("msg_signature") String msgSignature,
            @RequestParam("timestamp") String timestamp,
            @RequestParam("nonce") String nonce,
            @RequestBody String requestBody) {

        if (!weComBotService.isEnabled()) {
            return ResponseEntity.ok("");
        }

        try {
            // 解密消息
            WxCpDefaultConfigImpl config = weComBotService.getWxCpConfig();
            WxCpCryptUtil cryptUtil = new WxCpCryptUtil(config);
            String decryptedXml = cryptUtil.decrypt(msgSignature, timestamp, nonce, requestBody);

            // 手动解析 XML 提取关键字段（避免 fromXml 可见性问题）
            Document doc = DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder()
                    .parse(new ByteArrayInputStream(decryptedXml.getBytes(StandardCharsets.UTF_8)));
            Element root = doc.getDocumentElement();

            String msgType = getXmlText(root, "MsgType");
            String fromUser = getXmlText(root, "FromUserName");
            String content = getXmlText(root, "Content");

            // 只处理文本消息
            if ("text".equals(msgType) && fromUser != null && content != null) {
                weComBotService.handleMessage(fromUser, content);
            }

            return ResponseEntity.ok("");
        } catch (Exception e) {
            log.error("处理企业微信消息失败", e);
            return ResponseEntity.ok("");
        }
    }

    private String getXmlText(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() > 0) {
            return nodes.item(0).getTextContent();
        }
        return null;
    }
}
