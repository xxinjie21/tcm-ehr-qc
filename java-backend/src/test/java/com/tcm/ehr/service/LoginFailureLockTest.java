package com.tcm.ehr.service;

import com.tcm.ehr.common.utils.JwtUtil;
import com.tcm.ehr.mapper.OrgMapper;
import com.tcm.ehr.mapper.OrgMemberMapper;
import com.tcm.ehr.service.impl.AuthServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 登录失败锁定（批次 15 / P2-21）的契约。
 *
 * <p>P2-21 修的是一个<b>只能人工清理</b>的故障：{@code INCR} 与 {@code EXPIRE} 原先分两条命令发，
 * 若 {@code INCR} 成功而 {@code EXPIRE} 失败（超时 / 连接被回收 / 进程被杀），
 * 计数 key 就永久留在 Redis 里 —— 该账号从此无限期锁死，日志里也看不出发生过什么。
 * 改为一段 Lua 脚本后，两条命令在 Redis 端一次执行完，中间态不存在。</p>
 *
 * <p>本类把三件事钉住：① 记失败走的是<b>单次原子脚本调用</b>且 key 带统一前缀；
 * ② TTL 带抖动（15 ± 1–3 分钟）但恒为正；③ 锁定判定与「Redis 故障不锁」的既有口径不变。</p>
 */
class LoginFailureLockTest {

    private static final String FAIL_KEY = "tcm:ehr:auth:fail:alice";

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private AuthServiceImpl authService;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        authService = new AuthServiceImpl(mock(JwtUtil.class), mock(OrgMapper.class),
                mock(OrgMemberMapper.class), mock(IOrgService.class),
                mock(IOrgPermissionService.class), redis);
        ReflectionTestUtils.setField(authService, "loginMaxFail", 5);
        ReflectionTestUtils.setField(authService, "loginLockMinutes", 15);
    }

    @Test
    @DisplayName("记一次失败：单次原子脚本调用，key 带统一前缀，TTL 落在 15±3 分钟（P2-21）")
    void recordsFailureWithAtomicScriptAndJitteredTtl() {
        AtomicReference<String> keySeen = new AtomicReference<>();
        AtomicReference<String> ttlSeen = new AtomicReference<>();
        when(redis.execute(anyScript(), anyList(), any())).thenAnswer(inv -> {
            Object[] a = flattenVarargs(inv);
            keySeen.set(String.valueOf(((List<?>) a[1]).get(0)));
            ttlSeen.set(String.valueOf(a[2]));
            return 1L;
        });

        recordFailure("alice");

        assertEquals(FAIL_KEY, keySeen.get(), "计数 key 必须带统一前缀 tcm:ehr:");
        long ms = Long.parseLong(ttlSeen.get());
        assertTrue(ms >= 12 * 60_000L && ms <= 18 * 60_000L,
                "TTL 应落在 15±3 分钟（毫秒），实际 " + ms);
    }

    @Test
    @DisplayName("锁定 TTL 抖动：15 ± 1–3 分钟，且极小基准下也恒 ≥ 1 分钟（P2-21）")
    void lockTtlIsJitteredButAlwaysPositive() {
        for (int i = 0; i < 500; i++) {
            Duration ttl = ReflectionTestUtils.invokeMethod(authService, "lockTtl");
            assertNotNull(ttl);
            long minutes = ttl.toMinutes();
            assertTrue(minutes >= 12 && minutes <= 18, "应在 15±3 分钟内，实际 " + minutes);
        }
        // 基准配成 0 时也不能算出非正数：PEXPIRE 收到非正数等于立刻删除 key，也就是「不锁定」
        ReflectionTestUtils.setField(authService, "loginLockMinutes", 0);
        for (int i = 0; i < 200; i++) {
            Duration ttl = ReflectionTestUtils.invokeMethod(authService, "lockTtl");
            assertNotNull(ttl);
            assertTrue(ttl.toMinutes() >= 1, "TTL 至少 1 分钟，实际 " + ttl);
        }
    }

    @Test
    @DisplayName("Redis 不可用：记失败不抛异常，登录不被阻断（降级为无锁定）")
    void redisFailureDoesNotBlockLogin() {
        when(redis.execute(anyScript(), anyList(), any()))
                .thenThrow(new RuntimeException("redis down"));

        assertDoesNotThrow(() -> recordFailure("alice"),
                "计数写不进去只能降级，不能把一次 Redis 抖动升级成登录失败");
    }

    @Test
    @DisplayName("锁定判定：未达阈值不锁；达到阈值返回剩余秒数；无过期按整窗处理")
    void lockRemainingSecondsSemantics() {
        when(ops.get(FAIL_KEY)).thenReturn("2");
        assertEquals(0, remaining("alice"), "未达阈值不锁定");

        when(ops.get(FAIL_KEY)).thenReturn("5");
        when(redis.getExpire(FAIL_KEY, TimeUnit.SECONDS)).thenReturn(600L);
        assertEquals(600, remaining("alice"), "达到阈值应按 TTL 返回剩余秒数");

        // getExpire 返回 -1（无过期）：按「锁到窗口结束」处理，
        // 否则 Redis 里残留的无 TTL 计数会让账号被无限期锁死
        when(redis.getExpire(FAIL_KEY, TimeUnit.SECONDS)).thenReturn(-1L);
        assertEquals(15 * 60, remaining("alice"), "无 TTL 的残留计数按整窗处理");
    }

    @SuppressWarnings("unchecked")
    private static RedisScript<Long> anyScript() {
        return any();
    }

    private void recordFailure(String username) {
        ReflectionTestUtils.invokeMethod(authService, "recordLoginFailure", username);
    }

    private int remaining(String username) {
        Integer r = ReflectionTestUtils.invokeMethod(authService, "lockRemainingSeconds", username);
        return r == null ? -1 : r;
    }

    /**
     * 把 {@code (script, keys, ttl)} 三个实参摊平。
     *
     * <p>{@code execute} 的第三个参数是可变参数，Mockito 各版本对
     * 「{@code getArguments()} 是否展开可变参数」的处理不一致：有的给
     * {@code [script, keys, Object[]{ttl}]}，有的给 {@code [script, keys, ttl]}。
     * 这里两种都认，避免测试因 Mockito 版本而假红。</p>
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
