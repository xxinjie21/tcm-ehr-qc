package com.tcm.ehr.common.utils;

import com.tcm.ehr.common.exception.RateLimitedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 接口限流（P2-31 / R12）的契约。
 *
 * <p>实现已由「自写 ZSET 滑动窗口 + Lua」换成 Redisson {@code RRateLimiter}
 * （《自研组件替换计划》#1），本测试随之改为钉住 Redisson 的用法，而不是原脚本的细节。
 * 钉住四件事：① 判定走 {@code RRateLimiter.tryAcquire()}、key 带统一前缀
 * {@code tcm:ehr:rl:}；② <b>每次调用都要重设 rate 与 TTL</b> —— 前者是
 * {@code hsetnx} 语义（不重置已消耗额度，故可幂等重复调用；也正因如此不重设 TTL
 * 就会让「改限额」对老 key 永不生效），后者补上 Redisson 不设而原实现有的窗口回收；
 * ③ <b>降级方向是 fail-open</b> —— Redis 异常一律放行，不让一次抖动把 AI 全打成 429；
 * ④ 身份为空 / 关闭限流时不碰 Redis。</p>
 */
class RateLimiterTest {

    private static final String KEY = "tcm:ehr:rl:ai:u1";
    private static final Duration WINDOW = Duration.ofMinutes(5);

    private RedissonClient redisson;
    private RRateLimiter redisLimiter;
    private RateLimiter limiter;

    @BeforeEach
    void setUp() {
        redisson = mock(RedissonClient.class);
        redisLimiter = mock(RRateLimiter.class);
        when(redisson.getRateLimiter(anyString())).thenReturn(redisLimiter);
        limiter = new RateLimiter(redisson);
    }

    @Test
    @DisplayName("未达上限：tryAcquire=true → 放行；key = tcm:ehr:rl:{scope}:{identity}")
    void allowsWhenPermitAcquired() {
        when(redisLimiter.tryAcquire()).thenReturn(true);

        assertTrue(limiter.allow("ai", "u1", 60, WINDOW));

        verify(redisson).getRateLimiter(KEY);
    }

    @Test
    @DisplayName("达到上限：tryAcquire=false → 拒绝")
    void deniesWhenPermitRefused() {
        when(redisLimiter.tryAcquire()).thenReturn(false);

        assertFalse(limiter.allow("ai", "u1", 60, WINDOW));
    }

    @Test
    @DisplayName("每次调用都重设 rate 并补 TTL：trySetRate 是 hsetnx（不重置额度），TTL 是原实现有而 Redisson 没有的")
    void setsRateAndTtlOnEveryCall() {
        when(redisLimiter.tryAcquire()).thenReturn(true);

        limiter.allow("ai", "u1", 60, WINDOW);
        limiter.allow("ai", "u1", 60, WINDOW);

        // 连续两次都要重设：trySetRate 幂等且不重置已消耗额度，所以重复调用是安全的；
        // 而 TTL 必须每次都补 —— 否则 key 永不过期，改了限额也永远不生效。
        verify(redisLimiter, org.mockito.Mockito.times(2))
                .trySetRate(RateType.OVERALL, 60L, WINDOW);
        verify(redisLimiter, org.mockito.Mockito.times(2)).expire(WINDOW);
        verify(redisLimiter, org.mockito.Mockito.times(2)).tryAcquire();
    }

    @Test
    @DisplayName("Redis 不可用：放行（fail-open），不把一次抖动升级成 429")
    void redisFailureFailsOpen() {
        when(redisLimiter.tryAcquire()).thenThrow(new RuntimeException("redis down"));

        assertTrue(limiter.allow("ai", "u1", 60, WINDOW));
        assertDoesNotThrow(() -> limiter.check("ai", "u1", 60, WINDOW, "太频繁"));
    }

    @Test
    @DisplayName("身份为空：不计数直接放行（否则匿名请求共用一个桶，一人能挡住所有人）")
    void blankIdentitySkipsRedis() {
        assertTrue(limiter.allow("ai", null, 60, WINDOW));
        assertTrue(limiter.allow("ai", "   ", 60, WINDOW));

        verify(redisson, never()).getRateLimiter(anyString());
    }

    @Test
    @DisplayName("limit<=0：视为关闭限流，不碰 Redis")
    void nonPositiveLimitDisables() {
        assertTrue(limiter.allow("ai", "u1", 0, WINDOW));

        verify(redisson, never()).getRateLimiter(anyString());
    }

    @Test
    @DisplayName("check：超限抛 RateLimitedException（→ HTTP 429），未超限不抛")
    void checkThrowsOnlyWhenDenied() {
        when(redisLimiter.tryAcquire()).thenReturn(false);
        RateLimitedException e = assertThrows(RateLimitedException.class,
                () -> limiter.check("ai", "u1", 60, WINDOW, "AI 请求过于频繁，请稍后再试"));
        assertEquals("AI 请求过于频繁，请稍后再试", e.getMessage(), "提示文案要能直接展示给用户");

        when(redisLimiter.tryAcquire()).thenReturn(true);
        assertDoesNotThrow(() -> limiter.check("ai", "u1", 60, WINDOW, "AI 请求过于频繁，请稍后再试"));
    }
}
