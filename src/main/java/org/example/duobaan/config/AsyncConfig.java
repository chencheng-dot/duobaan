package org.example.duobaan.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SSE 流式输出的专用线程池（大模型流式读取期间阻塞，需独立线程）。
 */
@Configuration
public class AsyncConfig {

    @Bean(name = "sseExecutor", destroyMethod = "shutdown")
    public ExecutorService sseExecutor() {
        return Executors.newCachedThreadPool();
    }
}
