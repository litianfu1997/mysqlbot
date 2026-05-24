package com.example.mysqlbot.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.concurrent.Executors;

/**
 * 异步任务配置
 * 用于企业微信/飞书消息的异步处理
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean("imBotExecutor")
    public TaskExecutor imBotExecutor() {
        return new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor());
    }
}
