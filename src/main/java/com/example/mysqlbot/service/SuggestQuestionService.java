package com.example.mysqlbot.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 后续问题推荐服务
 * 使用智谱 LLM（zai-sdk）生成引导用户的 3 个推荐问题
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SuggestQuestionService {

    private final ZhipuLlmService zhipuLlmService;
    private final ObjectMapper objectMapper;

    /**
     * 生成推荐问题
     *
     * @param question 用户问题
     * @param sql      生成的 SQL
     * @return 推荐问题列表
     */
    public List<String> suggest(String question, String sql) {
        String promptTemplate = loadPromptTemplate();
        String prompt = promptTemplate
                .replace("{question}", question)
                .replace("{sql}", sql != null ? sql : "（无 SQL）");

        // 调用智谱 LLM（zai-sdk）
        String llmResponse = zhipuLlmService.chat(prompt, 0.5);
        log.debug("LLM 推荐问题响应:\n{}", llmResponse);

        return parseLlmResponse(llmResponse);
    }

    private List<String> parseLlmResponse(String response) {
        try {
            String cleanerJson = response.replaceAll("```json", "").replaceAll("```", "").trim();
            return objectMapper.readValue(cleanerJson, new TypeReference<List<String>>() {
            });
        } catch (JsonProcessingException e) {
            log.warn("解析 LLM 推荐问题失败: {}", response);
            // 降级处理：尝试按行分割
            List<String> list = new ArrayList<>();
            for (String line : response.split("\n")) {
                line = line.trim();
                if (line.startsWith("-") || line.matches("^\\d+\\..*")) {
                    line = line.replaceAll("^[-*\\d+..]\\s*", "");
                    if (!line.isEmpty())
                        list.add(line);
                }
            }
            return list.isEmpty() ? List.of("数据趋势是怎样的？", "可以按月对比吗？", "异常原因是什么？") : list;
        }
    }

    private String loadPromptTemplate() {
        try {
            ClassPathResource resource = new ClassPathResource("prompts/suggest-questions.st");
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("加载 Prompt 模板失败", e);
        }
    }
}
