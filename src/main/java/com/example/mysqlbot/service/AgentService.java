package com.example.mysqlbot.service;

import com.example.mysqlbot.util.OpenAiLlmUtil.ChatResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Agent service for tool use / function calling.
 * Manages multi-turn conversations where LLM can call tools to gather information.
 *
 * <p>The loop skeleton is decoupled from concrete tools via {@link ToolExecutor},
 * so SQL schema exploration, chart data probing, and suggest-relation probing
 * all reuse the same loop.
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
     * Tool executor: maps tool name + arguments to a result string.
     * Different agents inject their own execution logic.
     */
    @FunctionalInterface
    public interface ToolExecutor {
        String execute(String toolName, Map<String, Object> args);
    }

    /**
     * Builds a dataSourceId-bound executor: injects data_source_id then delegates to {@link ToolService}.
     */
    public ToolExecutor dataSourceToolExecutor(Long dataSourceId) {
        return (name, args) -> {
            args.put("data_source_id", dataSourceId);
            return toolService.executeTool(name, args);
        };
    }

    // ---------------------------------------------------------------------------
    // SQL schema tool loop (keeps original signature, delegates to generic loop)
    // ---------------------------------------------------------------------------

    /**
     * Agent loop: call LLM, detect tool_calls, execute tools, continue, return final text.
     * dataSourceId is injected server-side; LLM does not need (or is able) to pass data_source_id.
     */
    public String runAgentLoop(
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            Long dataSourceId,
            double temperature,
            com.example.mysqlbot.model.LlmConfig llmConfig) {
        return runAgentLoop(messages, tools, temperature, llmConfig,
                dataSourceToolExecutor(dataSourceId), maxToolRounds);
    }

    /**
     * Generic agent loop (uses default round limit mysqlbot.tool.max-rounds).
     */
    public String runAgentLoop(
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            double temperature,
            com.example.mysqlbot.model.LlmConfig llmConfig,
            ToolExecutor executor) {
        return runAgentLoop(messages, tools, temperature, llmConfig, executor, maxToolRounds);
    }

    /**
     * Generic agent loop: call LLM, detect tool_calls, execute via executor, continue, return final text.
     * After exceeding maxRounds, forces tool_choice=none to get a text answer.
     */
    public String runAgentLoop(
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            double temperature,
            com.example.mysqlbot.model.LlmConfig llmConfig,
            ToolExecutor executor,
            int maxRounds) {

        for (int round = 0; round < maxRounds; round++) {
            log.debug("Agent round {}/{}, messages={}, tools={}", round + 1, maxRounds,
                    messages.size(), tools != null ? tools.size() : 0);

            ChatResult result = llmService.chatWithMessagesAndTools(messages, tools, temperature, llmConfig);

            if (result.getToolCalls() == null || result.getToolCalls().isEmpty()) {
                String content = result.getContent() != null ? result.getContent() : "";
                log.debug("Agent loop finished after {} round(s), response {} chars", round + 1, content.length());
                return content;
            }

            log.info("Agent round {}: LLM requested {} tool call(s)", round + 1, result.getToolCalls().size());
            appendAssistantMessage(messages, result);
            executeToolCalls(messages, result, executor);
        }

        // Exceeded max rounds — force tool_choice=none so the model outputs text
        log.warn("Agent loop reached max rounds ({}), forcing final answer with tool_choice=none", maxRounds);
        ChatResult finalResult = llmService.chatWithMessagesAndTools(messages, null, temperature, llmConfig, "none");
        String finalContent = finalResult.getContent() != null ? finalResult.getContent() : "";
        if (finalContent.isBlank()) {
            log.error("Agent loop: final forced turn returned empty content");
            finalContent = "{\"success\":false,\"message\":\"tool call rounds exceeded limit, unable to generate answer\"}";
        }
        log.info("Agent loop forced final answer, response {} chars", finalContent.length());
        return finalContent;
    }

    /**
     * Tool exploration phase is non-streaming; final answer is streamed.
     * Used by generateStream: frontend waits during tool phase, then receives token stream.
     */
    public String runAgentLoopThenStream(
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            Long dataSourceId,
            double temperature,
            com.example.mysqlbot.model.LlmConfig llmConfig,
            boolean thinking,
            com.example.mysqlbot.util.OpenAiLlmUtil.StreamCallback tokenCallback) {

        ToolExecutor executor = dataSourceToolExecutor(dataSourceId);

        // Tool exploration phase (non-streaming)
        for (int round = 0; round < maxToolRounds; round++) {
            log.debug("AgentStream round {}/{}", round + 1, maxToolRounds);

            ChatResult result = llmService.chatWithMessagesAndTools(messages, tools, temperature, llmConfig);

            if (result.getToolCalls() == null || result.getToolCalls().isEmpty()) {
                // Model ready to answer directly, but this was a non-streaming result;
                // convert to streaming output for the frontend.
                log.debug("AgentStream: no more tool_calls after {} round(s), streaming final answer", round + 1);
                String content = result.getContent() != null ? result.getContent() : "";
                if (!content.isBlank()) {
                    tokenCallback.onToken("content", content);
                }
                return content;
            }

            log.info("AgentStream round {}: {} tool call(s)", round + 1, result.getToolCalls().size());
            appendAssistantMessage(messages, result);
            executeToolCalls(messages, result, executor);
        }

        // Exceeded round limit — final round goes real streaming (no tools)
        log.warn("AgentStream reached max rounds ({}), streaming final answer", maxToolRounds);
        return llmService.chatStreamObjectMessages(messages, temperature, llmConfig, thinking, tokenCallback);
    }

    // ---------------------------------------------------------------------------
    // Internal helpers
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

    private void executeToolCalls(List<Map<String, Object>> messages, ChatResult result, ToolExecutor executor) {
        var calls = result.getToolCalls();
        if (calls == null || calls.isEmpty()) return;

        // Filter out invalid tool calls, preserving index alignment
        List<com.example.mysqlbot.util.OpenAiLlmUtil.ChatResponse.Choice.ToolCall> validCalls = calls.stream()
                .filter(tc -> tc != null && tc.getFunction() != null)
                .collect(Collectors.toList());
        if (validCalls.isEmpty()) return;

        // Single tool call — execute directly (no thread overhead)
        if (validCalls.size() == 1) {
            messages.add(executeSingleToolCall(validCalls.get(0), executor));
            return;
        }

        // Multiple tool calls — execute in parallel (virtual threads enabled)
        log.info("Executing {} tool calls in parallel", validCalls.size());
        List<CompletableFuture<Map<String, Object>>> futures = validCalls.stream()
                .map(tc -> CompletableFuture.supplyAsync(() -> executeSingleToolCall(tc, executor)))
                .collect(Collectors.toList());

        // Wait for all, then append results in original order
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        for (CompletableFuture<Map<String, Object>> f : futures) {
            messages.add(f.join());
        }
    }

    private Map<String, Object> executeSingleToolCall(
            com.example.mysqlbot.util.OpenAiLlmUtil.ChatResponse.Choice.ToolCall tc, ToolExecutor executor) {
        Map<String, Object> toolArgs = parseArguments(tc.getFunction().getArguments());

        log.debug("Executing tool: {} args: {}", tc.getFunction().getName(), toolArgs);
        String toolResult = executor.execute(tc.getFunction().getName(), toolArgs);

        Map<String, Object> toolMsg = new HashMap<>();
        toolMsg.put("role", "tool");
        // If id is empty, generate a synthetic id to keep the conversation chain complete
        String callId = (tc.getId() != null && !tc.getId().isBlank())
                ? tc.getId()
                : "call_" + tc.getFunction().getName() + "_" + System.nanoTime();
        toolMsg.put("tool_call_id", callId);
        toolMsg.put("content", toolResult);
        return toolMsg;
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
