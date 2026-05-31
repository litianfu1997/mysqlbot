package com.example.mysqlbot.service;

import com.example.mysqlbot.util.OpenAiLlmUtil.ChatResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    private static final int MAX_TOOL_ROUNDS = 5;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Agent 循环：调用 LLM → 检测 tool_calls → 执行工具 → 继续循环 → 最终返回文本。
     *
     * @param messages 初始消息列表
     * @param tools    工具定义（null 表示不使用工具）
     * @param temperature LLM 温度
     * @param llmConfig LLM 配置
     * @return LLM 最终的文本响应
     */
    public String runAgentLoop(
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            double temperature,
            com.example.mysqlbot.model.LlmConfig llmConfig) {

        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            log.debug("Agent round {} of max {}, tools={}", round + 1, MAX_TOOL_ROUNDS,
                    tools != null ? tools.size() : 0);

            // 调用 LLM（非流式，Agent 模式下流式不适用）
            ChatResult result = llmService.chatWithMessagesAndTools(messages, tools, temperature, llmConfig);

            // 检查是否有 tool_calls
            if (result.getToolCalls() == null || result.getToolCalls().isEmpty()) {
                // 没有 tool_calls，返回文本内容
                String content = result.getContent();
                if (content == null) content = "";
                log.debug("Agent loop finished (no tool_calls), response length: {}", content.length());
                return content;
            }

            log.info("LLM requested {} tool calls", result.getToolCalls().size());

            // 追加 assistant 的 tool_calls 消息
            Map<String, Object> assistantMsg = new HashMap<>();
            assistantMsg.put("role", "assistant");
            assistantMsg.put("tool_calls", result.getToolCalls());
            // content 可以为 null（如果 LLM 只返回 tool_calls）
            if (result.getContent() != null && !result.getContent().isBlank()) {
                assistantMsg.put("content", result.getContent());
            }
            messages.add(assistantMsg);

            // 逐个执行工具调用，追加 tool 角色消息
            for (var tc : result.getToolCalls()) {
                if (tc == null || tc.getFunction() == null) continue;

                Map<String, Object> toolArgs = parseArguments(tc.getFunction().getArguments());
                log.debug("Executing tool: {} with args: {}", tc.getFunction().getName(), toolArgs);

                String toolResult = toolService.executeTool(
                        tc.getFunction().getName(),
                        toolArgs);

                Map<String, Object> toolMsg = new HashMap<>();
                toolMsg.put("role", "tool");
                toolMsg.put("tool_call_id", tc.getId());
                toolMsg.put("content", toolResult);
                messages.add(toolMsg);
            }
        }

        // 超过最大轮次，强制最后一次调用（不带 tools）
        ChatResult finalResult = llmService.chatWithMessagesAndTools(messages, null, temperature, llmConfig);
        String finalContent = finalResult.getContent();
        if (finalContent == null) finalContent = "";
        log.info("Agent loop exceeded max rounds, forcing final LLM call, response length: {}", finalContent.length());
        return finalContent;
    }

    /**
     * 解析工具调用参数（JSON 字符串 → Map）。
     */
    private Map<String, Object> parseArguments(String argumentsJson) {
        try {
            if (argumentsJson == null || argumentsJson.isBlank()) {
                return Map.of();
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
            log.warn("Failed to parse tool arguments: {}, error: {}", argumentsJson, e.getMessage());
            return Map.of();
        }
    }
}
