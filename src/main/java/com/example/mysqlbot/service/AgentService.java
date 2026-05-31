package com.example.mysqlbot.service;

import com.example.mysqlbot.util.OpenAiLlmUtil.ChatResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Agent service for tool use / function calling.
 * Manages multi-turn conversations where LLM can call tools to gather information.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {

    private final LlmService llmService;
    private final ToolService toolService;

    @Value("${mysqlbot.tool.max-rounds:8}")
    private int maxToolRounds;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Agent 循环：调用 LLM → 检测 tool_calls → 执行工具 → 继续循环 → 最终返回文本。
     * dataSourceId 由后端权威注入，LLM 无需（也无法）自己传 data_source_id。
     */
    public String runAgentLoop(
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            Long dataSourceId,
            double temperature,
            com.example.mysqlbot.model.LlmConfig llmConfig) {

        for (int round = 0; round < maxToolRounds; round++) {
            log.debug("Agent round {}/{}, messages={}, tools={}", round + 1, maxToolRounds,
                    messages.size(), tools != null ? tools.size() : 0);

            ChatResult result = llmService.chatWithMessagesAndTools(messages, tools, temperature, llmConfig);

            if (result.getToolCalls() == null || result.getToolCalls().isEmpty()) {
                String content = result.getContent() != null ? result.getContent() : "";
                log.debug("Agent loop finished after {} round(s), response {} chars", round + 1, content.length());
                return content;
            }

            log.info("Agent round {}: LLM requested {} tool call(s)", round + 1, result.getToolCalls().size());
            appendAssistantMessage(messages, result);
            executeToolCalls(messages, result, dataSourceId);
        }

        // 超过最大轮次 — 最终调用强制 tool_choice=none，让模型输出文本
        log.warn("Agent loop reached max rounds ({}), forcing final answer with tool_choice=none", maxToolRounds);
        ChatResult finalResult = llmService.chatWithMessagesAndTools(messages, null, temperature, llmConfig, "none");
        String finalContent = finalResult.getContent() != null ? finalResult.getContent() : "";
        if (finalContent.isBlank()) {
            log.error("Agent loop: final forced turn returned empty content");
            finalContent = "{\"success\":false,\"message\":\"模型工具调用超出轮次上限，无法生成答案\"}";
        }
        log.info("Agent loop forced final answer, response {} chars", finalContent.length());
        return finalContent;
    }

    /**
     * 工具探索阶段非流式，最终答案轮流式输出。
     * 用于 generateStream：前端在工具阶段等待、最终轮收到 token 流。
     */
    public String runAgentLoopThenStream(
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            Long dataSourceId,
            double temperature,
            com.example.mysqlbot.model.LlmConfig llmConfig,
            boolean thinking,
            com.example.mysqlbot.util.OpenAiLlmUtil.StreamCallback tokenCallback) {

        // 工具探索阶段（非流式）
        for (int round = 0; round < maxToolRounds; round++) {
            log.debug("AgentStream round {}/{}", round + 1, maxToolRounds);

            ChatResult result = llmService.chatWithMessagesAndTools(messages, tools, temperature, llmConfig);

            if (result.getToolCalls() == null || result.getToolCalls().isEmpty()) {
                // 模型已准备好直接回答，但此次是非流式结果；转换为流式输出给前端
                log.debug("AgentStream: no more tool_calls after {} round(s), streaming final answer", round + 1);
                String content = result.getContent() != null ? result.getContent() : "";
                if (!content.isBlank()) {
                    tokenCallback.onToken("content", content);
                }
                return content;
            }

            log.info("AgentStream round {}: {} tool call(s)", round + 1, result.getToolCalls().size());
            appendAssistantMessage(messages, result);
            executeToolCalls(messages, result, dataSourceId);
        }

        // 超过轮次上限 — 最终一轮走真流式（无工具）
        log.warn("AgentStream reached max rounds ({}), streaming final answer", maxToolRounds);
        return llmService.chatStreamObjectMessages(messages, temperature, llmConfig, thinking, tokenCallback);
    }

    // ---------------------------------------------------------------------------
    // 内部辅助方法
    // ---------------------------------------------------------------------------

    private void appendAssistantMessage(List<Map<String, Object>> messages, ChatResult result) {
        Map<String, Object> assistantMsg = new HashMap<>();
        assistantMsg.put("role", "assistant");
        assistantMsg.put("tool_calls", result.getToolCalls());
        if (result.getContent() != null && !result.getContent().isBlank()) {
            assistantMsg.put("content", result.getContent());
        }
        messages.add(assistantMsg);
    }

    private void executeToolCalls(List<Map<String, Object>> messages, ChatResult result, Long dataSourceId) {
        for (var tc : result.getToolCalls()) {
            if (tc == null || tc.getFunction() == null) continue;

            Map<String, Object> toolArgs = parseArguments(tc.getFunction().getArguments());
            // 后端权威注入 dataSourceId，覆盖 LLM 可能传的任何值
            toolArgs.put("data_source_id", dataSourceId);

            log.debug("Executing tool: {} args: {}", tc.getFunction().getName(), toolArgs);
            String toolResult = toolService.executeTool(tc.getFunction().getName(), toolArgs);

            Map<String, Object> toolMsg = new HashMap<>();
            toolMsg.put("role", "tool");
            // 若 id 为空，生成合成 id 保证消息链完整
            String callId = (tc.getId() != null && !tc.getId().isBlank())
                    ? tc.getId()
                    : "call_" + tc.getFunction().getName() + "_" + System.nanoTime();
            toolMsg.put("tool_call_id", callId);
            toolMsg.put("content", toolResult);
            messages.add(toolMsg);
        }
    }

    private Map<String, Object> parseArguments(String argumentsJson) {
        try {
            if (argumentsJson == null || argumentsJson.isBlank()) {
                return new HashMap<>();
            }
            JsonNode node = objectMapper.readTree(argumentsJson);
            Map<String, Object> args = new HashMap<>();
            if (node.isObject()) {
                var iterator = node.fields();
                while (iterator.hasNext()) {
                    var entry = iterator.next();
                    try {
                        args.put(entry.getKey(), objectMapper.convertValue(entry.getValue(), Object.class));
                    } catch (Exception e) {
                        args.put(entry.getKey(), entry.getValue().asText());
                    }
                }
            }
            return args;
        } catch (Exception e) {
            log.warn("Failed to parse tool arguments: '{}', error: {}", argumentsJson, e.getMessage());
            return new HashMap<>();
        }
    }
}
