package org.example.duobaan.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SSE 流式输出的专用线程池（大模型流式读取期间阻塞，需独立线程）。
 * 使用有界队列 + 固定上限，避免无界线程池被大量并发流耗尽。
 */
@Configuration
public class AsyncConfig {

    @Bean(name = "sseExecutor", destroyMethod = "shutdown")
    public ExecutorService sseExecutor() {
        AtomicInteger seq = new AtomicInteger();
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "sse-stream-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        return new ThreadPoolExecutor(8, 32, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(64), tf, new ThreadPoolExecutor.AbortPolicy());
    }
}
