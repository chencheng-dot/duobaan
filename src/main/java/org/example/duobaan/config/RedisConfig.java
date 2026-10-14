package org.example.duobaan.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

/**
 * Redis 配置：只提供 JedisPool（业务唯一使用的客户端，见 RedisVectorService）。
 * 连接参数来自 application.properties 的 spring.data.redis.* 。
 *
 * 说明：此前同时定义了 JedisPool 与 JedisConnectionFactory(+StringRedisTemplate)，等于并起两套客户端。
 * 但只删掉那两个 Bean 并不能真正收敛——只要 spring-data-redis 在类路径上，
 * DataRedisAutoConfiguration 就会在 @ConditionalOnMissingBean(RedisConnectionFactory) 重新满足后
 * 再造出 JedisConnectionFactory / RedisTemplate / StringRedisTemplate（pom 里保留 jedis、
 * 未配置 spring.data.redis.client-type → matchIfMissing=true）。
 * 因此 application.properties 里同时排除了 DataRedisAutoConfiguration 与 DataRedisRepositoriesAutoConfiguration，
 * 这才是"只留一套客户端"；若将来要用 RedisTemplate，记得把那个排除项去掉。
 */
@Configuration
public class RedisConfig {

    @Value("${spring.data.redis.host:localhost}")
    private String host;

    @Value("${spring.data.redis.port:6379}")
    private int port;

    @Value("${spring.data.redis.password:}")
    private String password;

    @Value("${spring.data.redis.database:0}")
    private int database;

    @Value("${spring.data.redis.timeout:3000}")
    private Duration timeout;

    @Bean
    public JedisPool jedisPool() {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(16);
        poolConfig.setMaxIdle(8);
        poolConfig.setMinIdle(2);
        // 显式限制「借连接」的等待时间：默认 -1 = 无限等待，
        // Redis 不可用时会把请求线程永久挂住（连带 32 个 SSE 线程占满），1s 超时后快速失败交给上层降级。
        poolConfig.setMaxWait(Duration.ofMillis(1000));
        int timeoutMs = (int) timeout.toMillis();
        String pwd = (password == null || password.isBlank()) ? null : password;
        if (pwd == null) {
            return new JedisPool(poolConfig, host, port, timeoutMs, null, database);
        }
        return new JedisPool(poolConfig, host, port, timeoutMs, pwd, database);
    }
}
