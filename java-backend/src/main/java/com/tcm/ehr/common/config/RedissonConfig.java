package com.tcm.ehr.common.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 客户端装配（批次 16.1）。
 *
 * <p>用 <b>redisson 核心库</b>而不是 starter：starter 的 spring-data 适配器只到 Boot 3.4，
 * 本项目是 Boot 4，硬套等于把 Redis 客户端能力与 Spring Data 版本耦合起来；
 * 这里只需要「一个能拿分布式锁的 RedissonClient」，手工装配反而更小更稳。</p>
 *
 * <p>参数一律沿用 {@code spring.data.redis.*}（与已有的 StringRedisTemplate / Lettuce
 * 同一份配置），不在 yml 里再开一段 Redisson 专属配置 —— 两份地址配置会漂移。</p>
 */
@Configuration
public class RedissonConfig {

    /**
     * 连接与命令超时 3s（不是 yml 里的 5000ms）。
     *
     * <p>锁路径要<b>快速失败</b>：Redis 不可用时请求线程最多等 3s（与
     * {@link com.tcm.ehr.common.utils.DistLock} 的取锁等待一致）就该拿到明确错误，
     * 而不是先等 5s I/O 再报「未知异常」。</p>
     */
    private static final int TIMEOUT_MS = 3000;

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(
            @Value("${spring.data.redis.host:localhost}") String host,
            @Value("${spring.data.redis.port:6379}") int port,
            @Value("${spring.data.redis.password:}") String password,
            @Value("${spring.data.redis.database:0}") int database) {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setDatabase(database)
                .setConnectTimeout(TIMEOUT_MS)
                .setTimeout(TIMEOUT_MS)
                // 不重试：离线 Redis 时重试只是把 3s 拖成 3s×N，锁拿不到就是拿不到
                .setRetryAttempts(1)
                .setRetryInterval(200);
        if (password != null && !password.isBlank()) {
            config.useSingleServer().setPassword(password);
        }
        return Redisson.create(config);
    }
}
