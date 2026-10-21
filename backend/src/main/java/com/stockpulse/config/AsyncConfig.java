package com.stockpulse.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.concurrent.Executors;

/**
 * Suggestion generation runs off the request thread. The work is I/O-bound (an LLM call that can
 * take seconds), so a virtual thread per task is cheap and never starves a fixed pool.
 * Scheduling drives {@code StaleSuggestionSweeper}.
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    @Bean(name = "taskExecutor")
    public AsyncTaskExecutor taskExecutor() {
        return new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor());
    }
}
