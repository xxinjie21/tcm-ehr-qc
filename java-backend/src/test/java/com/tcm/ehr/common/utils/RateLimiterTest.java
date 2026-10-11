package com.tcm.ehr.common.utils;

import com.tcm.ehr.common.exception.RateLimitedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 接口限流（P2-31 / R12）的契约。
 *
 * <p>钉住四件事：① 判定走<b>单次 Lua 调用</b>、key 带统一前缀 {@code tcm:ehr:rl:}；
 * ② 窗口按毫秒、上限按值传给脚本，且同一毫秒的两次请求 member 不重合
 * （ZSET 同 member 只算一条，重合会让第二次请求被静默吞掉、限流形同虚设）；
 * ③ <b>降级方向是 fail-open</b> —— Redis 不可用、脚本无返回都放行，
 * 不让一次 Redis 抖动把 AI 全打成 429；④ 身份为空 / 关闭限流时不碰 Redis。</p>
 */
class RateLimiterTest {

    private static final String KEY = "tcm:ehr:rl:ai:u1";
    private static final Duration WINDOW = Duration.ofMinutes(5);

    private StringRedisTemplate redis;
    private RateLimiter limiter;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        limiter = new RateLimiter(redis);
    }

    @Test
    @DisplayName("未达上限：单次脚本调用返回 1 → 放行；key = tcm:ehr:rl:{scope}:{identity}")
    void allowsWithSingleScriptCallAndUnifiedKey() {
        AtomicReference<String> keySeen = new AtomicReference<>();
        when(redis.execute(anyScript(), anyList(), any(), any(), any(), any())).thenAnswer(inv -> {
            keySeen.set(String.valueOf(((List<?>) flattenVarargs(inv)[1]).get(0)));
            return 1L;
        });

        assertTrue(limiter.allow("ai", "u1", 60, WINDOW));

        assertEquals(KEY, keySeen.get(), "key 必须带统一前缀 + 业务域 + 身份");
        verify(redis, times(1)).execute(anyScript(), anyList(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("达到上限：脚本返回 0 → 拒绝")
    void deniesWhenScriptReturnsZero() {
        when(redis.execute(anyScript(), anyList(), any(), any(), any(), any())).thenReturn(0L);

        assertFalse(limiter.allow("ai", "u1", 60, WINDOW));
    }

    @Test
    @DisplayName("脚本参数依次为 now / window(毫秒) / limit / member，且 member 同毫秒内也不重合")
    void passesWindowMillisLimitAndUniqueMember() {
        List<String> members = new ArrayList<>();
        AtomicReference<Object[]> last = new AtomicReference<>();
        when(redis.execute(anyScript(), anyList(), any(), any(), any(), any())).thenAnswer(inv -> {
            Object[] a = flattenVarargs(inv);
            last.set(a);
            members.add(String.valueOf(a[5]));
            return 1L;
        });

        limiter.allow("ai", "u1", 60, WINDOW);
        limiter.allow("ai", "u1", 60, WINDOW);

        Object[] a = last.get();
        assertEquals(6, a.length, "实参应为 (script, keys, now, window, limit, member)");
        assertEquals(String.valueOf(WINDOW.toMillis()), String.valueOf(a[3]), "窗口按毫秒传给脚本");
        assertEquals("60", String.valueOf(a[4]), "上限值传给脚本");
        assertTrue(Long.parseLong(String.valueOf(a[2])) > 0, "now 应为正的时间戳");
        assertEquals(2, members.size());
        assertNotEquals(members.get(0), members.get(1),
                "同一毫秒的两次请求 member 也必须不同，否则 ZSET 会互相覆盖");
    }

    @Test
    @DisplayName("Redis 不可用：放行（fail-open），不把一次抖动升级成 429")
    void redisFailureFailsOpen() {
        when(redis.execute(anyScript(), anyList(), any(), any(), any(), any())).thenThrow(new RuntimeException("redis down"));

        assertTrue(limiter.allow("ai", "u1", 60, WINDOW));
        assertDoesNotThrow(() -> limiter.check("ai", "u1", 60, WINDOW, "太频繁"));
    }

    @Test
    @DisplayName("脚本无返回（null）：按放行处理")
    void nullResultFailsOpen() {
        when(redis.execute(anyScript(), anyList(), any(), any(), any(), any())).thenReturn(null);

        assertTrue(limiter.allow("ai", "u1", 60, WINDOW));
    }

    @Test
    @DisplayName("身份为空：不计数直接放行（否则匿名请求共用一个桶，一人能挡住所有人）")
    void blankIdentitySkipsRedis() {
        assertTrue(limiter.allow("ai", null, 60, WINDOW));
        assertTrue(limiter.allow("ai", "   ", 60, WINDOW));

        verify(redis, never()).execute(anyScript(), anyList(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("limit<=0：视为关闭限流，不碰 Redis")
    void nonPositiveLimitDisables() {
        assertTrue(limiter.allow("ai", "u1", 0, WINDOW));

        verify(redis, never()).execute(anyScript(), anyList(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("check：超限抛 RateLimitedException（→ HTTP 429），未超限不抛")
    void checkThrowsOnlyWhenDenied() {
        when(redis.execute(anyScript(), anyList(), any(), any(), any(), any())).thenReturn(0L);
        RateLimitedException e = assertThrows(RateLimitedException.class,
                () -> limiter.check("ai", "u1", 60, WINDOW, "AI 请求过于频繁，请稍后再试"));
        assertEquals("AI 请求过于频繁，请稍后再试", e.getMessage(), "提示文案要能直接展示给用户");

        when(redis.execute(anyScript(), anyList(), any(), any(), any(), any())).thenReturn(1L);
        assertDoesNotThrow(() -> limiter.check("ai", "u1", 60, WINDOW, "AI 请求过于频繁，请稍后再试"));
    }

    @SuppressWarnings("unchecked")
    private static RedisScript<Long> anyScript() {
        return any();
    }

    /**
     * 把 {@code (script, keys, now, window, limit, member)} 摊平。
     *
     * <p>{@code execute} 的第三个参数是可变参数，Mockito 各版本对
     * 「{@code getArguments()} 是否展开可变参数」处理不一致：有的给
     * {@code [script, keys, Object[]{...}]}，有的给 {@code [script, keys, ...]}。
     * 两种都认，避免测试因 Mockito 版本而假红。</p>
     */
    private static Object[] flattenVarargs(InvocationOnMock inv) {
        Object[] raw = inv.getArguments();
        if (raw.length >= 3 && raw[2] instanceof Object[] tail) {
            Object[] out = new Object[raw.length - 1 + tail.length];
            System.arraycopy(raw, 0, out, 0, raw.length - 1);
            System.arraycopy(tail, 0, out, raw.length - 1, tail.length);
            return out;
        }
        return raw;
    }
}
