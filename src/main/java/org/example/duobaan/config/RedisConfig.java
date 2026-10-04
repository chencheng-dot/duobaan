package org.example.duobaan.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

/**
 * Redis 配置：提供 JedisPool（向量存取）+ StringRedisTemplate（备用）。
 * 连接参数来自 application.properties 的 spring.data.redis.* 。
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
        int timeoutMs = (int) timeout.toMillis();
        String pwd = (password == null || password.isBlank()) ? null : password;
        if (pwd == null) {
            return new JedisPool(poolConfig, host, port, timeoutMs, null, database);
        }
        return new JedisPool(poolConfig, host, port, timeoutMs, pwd, database);
    }

    @Bean
    public JedisConnectionFactory jedisConnectionFactory() {
        RedisStandaloneConfiguration cfg = new RedisStandaloneConfiguration(host, port);
        cfg.setDatabase(database);
        if (password != null && !password.isBlank()) {
            cfg.setPassword(password);
        }
        return new JedisConnectionFactory(cfg);
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(JedisConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }
}
