package com.example.mysqlbot.service;

import com.example.mysqlbot.service.AgentService.ToolExecutor;
import com.example.mysqlbot.util.OpenAiLlmUtil.ChatResponse;
import com.example.mysqlbot.util.OpenAiLlmUtil.ChatResult;
import com.example.mysqlbot.util.OpenAiLlmUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link AgentService}, focusing on parallel tool execution.
 */
@ExtendWith(MockitoExtension.class)
class AgentServiceTest {

    @Mock
    private LlmService llmService;

    @Mock
    private ToolService toolService;

    // ---- Parallel execution: timing ----

    @Test
    void multipleToolCalls_executeInParallel() {
        AgentService agentService = new AgentService(llmService, toolService);

        // Each tool call sleeps 300ms; 3 calls serial = 900ms, parallel ~= 300ms
        ToolExecutor trackingExecutor = (name, args) -> {
            try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return "result-" + name;
        };

        List<ChatResponse.Choice.ToolCall> toolCalls = List.of(
                makeToolCall("call_1", "get_table_schema"),
                makeToolCall("call_2", "get_table_schema"),
                makeToolCall("call_3", "get_table_schema")
        );

        when(llmService.chatWithMessagesAndTools(anyList(), anyList(), anyDouble(), any()))
                .thenReturn(new ChatResult(null, toolCalls, "tool_calls"))   // round 1: tool calls
                .thenReturn(new ChatResult("final answer", null, "stop"));   // round 2: final answer

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", "test"));

        long start = System.currentTimeMillis();
        String result = agentService.runAgentLoop(
                messages, new ArrayList<>(), 0.3, null, trackingExecutor, 5);
        long elapsed = System.currentTimeMillis() - start;

        assertEquals("final answer", result);

        // 3 tools x 300ms each. Serial = 900ms. Parallel should be well under that.
        assertTrue(elapsed < 800,
                "3 parallel 300ms calls should complete in < 800ms, took " + elapsed + "ms");
    }

    // ---- Parallel execution: result ordering ----

    @Test
    void multipleToolCalls_preserveOrderInMessages() {
        AgentService agentService = new AgentService(llmService, toolService);

        AtomicInteger counter = new AtomicInteger(0);
        Map<String, Integer> executionOrder = new ConcurrentHashMap<>();

        ToolExecutor executor = (name, args) -> {
            int seq = counter.incrementAndGet();
            executionOrder.put(name, seq);
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return name + "-done";
        };

        List<ChatResponse.Choice.ToolCall> toolCalls = List.of(
                makeToolCall("call_1", "tool_a"),
                makeToolCall("call_2", "tool_b"),
                makeToolCall("call_3", "tool_c")
        );

        when(llmService.chatWithMessagesAndTools(anyList(), anyList(), anyDouble(), any()))
                .thenReturn(new ChatResult(null, toolCalls, "tool_calls"))
                .thenReturn(new ChatResult("done", null, "stop"));

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", "test"));

        agentService.runAgentLoop(messages, new ArrayList<>(), 0.3, null, executor, 5);

        // Tool messages should be in the same order as the tool calls
        List<Map<String, Object>> toolMessages = messages.stream()
                .filter(m -> "tool".equals(m.get("role")))
                .toList();

        assertEquals(3, toolMessages.size());
        // tool_call_id should match the original call order
        assertEquals("call_1", toolMessages.get(0).get("tool_call_id"));
        assertEquals("call_2", toolMessages.get(1).get("tool_call_id"));
        assertEquals("call_3", toolMessages.get(2).get("tool_call_id"));
    }

    // ---- Single tool call: no parallel overhead ----

    @Test
    void singleToolCall_executesDirectly() {
        AgentService agentService = new AgentService(llmService, toolService);

        ToolExecutor executor = (name, args) -> "single-result";

        List<ChatResponse.Choice.ToolCall> toolCalls = List.of(
                makeToolCall("call_1", "list_tables")
        );

        when(llmService.chatWithMessagesAndTools(anyList(), anyList(), anyDouble(), any()))
                .thenReturn(new ChatResult(null, toolCalls, "tool_calls"))
                .thenReturn(new ChatResult("answer", null, "stop"));

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", "test"));

        String result = agentService.runAgentLoop(messages, new ArrayList<>(), 0.3, null, executor, 5);

        assertEquals("answer", result);

        // Verify the tool message was appended
        List<Map<String, Object>> toolMessages = messages.stream()
                .filter(m -> "tool".equals(m.get("role")))
                .toList();
        assertEquals(1, toolMessages.size());
        assertEquals("single-result", toolMessages.get(0).get("content"));
    }

    // ---- No tool calls: returns content immediately ----

    @Test
    void noToolCalls_returnsContentImmediately() {
        AgentService agentService = new AgentService(llmService, toolService);

        when(llmService.chatWithMessagesAndTools(anyList(), anyList(), anyDouble(), any()))
                .thenReturn(new ChatResult("quick answer", null, "stop"));

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", "hi"));

        String result = agentService.runAgentLoop(messages, new ArrayList<>(), 0.3, null,
                (name, args) -> "should-not-be-called", 5);

        assertEquals("quick answer", result);
    }

    // ---- Helper ----

    private ChatResponse.Choice.ToolCall makeToolCall(String id, String toolName) {
        ChatResponse.Choice.ToolCall tc = new ChatResponse.Choice.ToolCall();
        tc.setId(id);
        tc.setType("function");
        ChatResponse.Choice.ToolCall.FunctionCall fc = new ChatResponse.Choice.ToolCall.FunctionCall();
        fc.setName(toolName);
        fc.setArguments("{}");
        tc.setFunction(fc);
        return tc;
    }
}