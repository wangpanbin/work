package com.cinema.config;

import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.time.Duration;

/**
 * Redis 配置(Phase A-②)
 * <ul>
 *   <li>删除了原 redisTemplate(String, Object) Bean —— 该 Bean 实际未被任何业务类注入, 是死代码</li>
 *   <li>业务统一使用 Spring Boot 自动装配的 StringRedisTemplate</li>
 *   <li>Lettuce commandTimeout = 200ms, 防止单命令阻塞拖垮 Netty EventLoop</li>
 * </ul>
 */
@Configuration
public class RedisConfig {

    /**
     * Lettuce 客户端配置: 显式设 commandTimeout.
     * Lettuce 基于 Netty, 单连接异步, 配合连接池与超时避免极端长尾.
     */
    @Bean
    public LettuceClientConfigurationBuilderCustomizer lettuceCustomizer() {
        return builder -> builder.commandTimeout(Duration.ofMillis(200));
    }
}
