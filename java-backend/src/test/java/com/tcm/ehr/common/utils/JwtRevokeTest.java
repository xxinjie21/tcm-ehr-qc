package com.tcm.ehr.common.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 令牌作废（批次 15）的契约。
 *
 * <p>JWT 无状态：签发后在有效期内一直有效，改密码 / 退出登录都不会让已发出去的
 * 那张令牌立刻失效 —— 复制到别的浏览器照样能调接口。这里用「每用户一个令牌版本号」
 * 解决：{@code revokeAll} 让版本 +1，令牌里的 {@code ver} 对不上即失效。</p>
 *
 * <p>同时锁住一条刻意取舍：<b>Redis 不可用时降级放行</b>（不比对版本）。
 * 否则一次 Redis 抖动会把全站已登录用户瞬间踢下线。</p>
 */
class JwtRevokeTest {

    private static final String SECRET = "tcm-ehr-qc-jwt-secret-key-2026-course-design";

    private JwtUtil newUtil(StringRedisTemplate redis) {
        JwtUtil u = new JwtUtil(redis);
        ReflectionTestUtils.setField(u, "secret", SECRET);
        ReflectionTestUtils.setField(u, "expireHours", 24L);
        return u;
    }

    @SuppressWarnings("unchecked")
    private ValueOperations<String, String> ops(StringRedisTemplate redis) {
        ValueOperations<String, String> v = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(v);
        return v;
    }

    @Test
    @DisplayName("刚签发的令牌可用")
    void freshTokenIsValid() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> v = ops(redis);
        when(v.get(anyString())).thenReturn(null);
        JwtUtil u = newUtil(redis);
        assertNotNull(u.parseAndVerify(u.generateToken("u1", "alice", "用户")));
    }

    @Test
    @DisplayName("作废后：该用户此前所有令牌立即失效")
    void revokedTokenBecomesInvalid() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> v = ops(redis);
        // 用户尚未被作废过 → 无版本记录
        when(v.get(anyString())).thenReturn(null);
        JwtUtil u = newUtil(redis);
        String token = u.generateToken("u1", "alice", "用户");
        assertNotNull(u.parseAndVerify(token));

        // 退出登录：Redis 里的当前版本变成 "1"
        when(v.get("tcm:ehr:auth:ver:u1")).thenReturn("1");
        assertNull(u.parseAndVerify(token), "版本不一致的令牌必须失效");
    }

    @Test
    @DisplayName("版本一致时仍有效（改密码后重新登录签发的新令牌能用）")
    void matchingVersionStaysValid() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> v = ops(redis);
        when(v.get("tcm:ehr:auth:ver:u1")).thenReturn("3");
        JwtUtil u = newUtil(redis);
        // 新令牌带 ver=3（currentVersion 读到 3）
        String token = u.generateToken("u1", "alice", "用户");
        assertNotNull(u.parseAndVerify(token));
    }

    @Test
    @DisplayName("令牌版本按用户隔离：作废 A 不影响 B")
    void revokeIsPerUser() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> v = ops(redis);
        JwtUtil u = newUtil(redis);
        String tokenA = u.generateToken("uA", "alice", "用户");
        String tokenB = u.generateToken("uB", "bob", "用户");

        // 只有 A 的版本被 +1
        when(v.get("tcm:ehr:auth:ver:uA")).thenReturn("1");
        when(v.get("tcm:ehr:auth:ver:uB")).thenReturn(null);

        assertNull(u.parseAndVerify(tokenA), "A 已登出");
        assertNotNull(u.parseAndVerify(tokenB), "B 不该被牵连");
    }

    @Test
    @DisplayName("Redis 不可用：降级放行（不因一次抖动把全站踢下线）")
    void redisDownFailsOpen() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        // opsForValue 抛异常 = Redis 挂
        when(redis.opsForValue()).thenThrow(new RuntimeException("redis down"));
        JwtUtil u = newUtil(redis);
        String token = u.generateToken("u1", "alice", "用户");
        // 签名与有效期仍校验，只是跳过版本比对
        assertNotNull(u.parseAndVerify(token), "Redis 故障时应降级放行，保证可用性");
    }

    @Test
    @DisplayName("篡改签名 / 非法令牌的令牌仍无效（版本机制不放松既有校验）")
    void tamperedTokenStillInvalid() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> v = ops(redis);
        when(v.get(anyString())).thenReturn(null);
        JwtUtil u = newUtil(redis);
        assertNull(u.parseAndVerify("not-a-jwt"), "非法令牌必须无效");
        String token = u.generateToken("u1", "alice", "用户");
        assertNull(u.parseAndVerify(token.substring(0, token.length() - 2) + "xx"), "被篡改的令牌必须无效");
    }

    @Test
    @DisplayName("无 ver claim 的老令牌：当前无版本记录时仍认（平滑升级）")
    void legacyTokenWithoutVerClaim() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> v = ops(redis);
        when(v.get(anyString())).thenReturn(null);
        JwtUtil u = newUtil(redis);
        // 手工造一个不含 ver 的令牌（模拟升级前签发的）
        String legacy = io.jsonwebtoken.Jwts.builder()
                .setSubject("u1")
                .claim("username", "alice")
                .claim("role", "用户")
                .setIssuedAt(new java.util.Date())
                .setExpiration(new java.util.Date(System.currentTimeMillis() + 86400000L))
                .signWith(ReflectionTestUtils.invokeMethod(u, "getKey"),
                        io.jsonwebtoken.SignatureAlgorithm.HS256)
                .compact();
        assertNotNull(u.parseAndVerify(legacy), "尚未发生作废时，老令牌应继续可用");
    }

    // ------------------------------------------- P2-20：占位版本号 + TTL 抖动

    /**
     * 从未作废过的用户：签发令牌时必须落一个值为 {@code "0"} 的占位 key。
     *
     * <p>没有这个占位，key 一旦因 TTL 到期消失，计数就从 {@code null + 1 = 1} 重新开始；
     * 若此前已作废过一次（版本也是 1），那批旧令牌的 {@code ver=1} 会重新匹配上
     * —— 也就是「已作废的令牌复活」。</p>
     */
    @Test
    @DisplayName("从未作废过的用户：签发时落占位版本号 \"0\"，TTL 覆盖令牌寿命（P2-20）")
    void anchorWritesPlaceholderForNeverRevokedUser() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> v = ops(redis);
        when(v.get(anyString())).thenReturn(null);
        // key 不存在 → EXPIRE 返回 false，走占位写入
        when(redis.expire(anyString(), any(Duration.class))).thenReturn(false);
        JwtUtil u = newUtil(redis);

        u.generateToken("u1", "alice", "用户");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(v).setIfAbsent(eq("tcm:ehr:auth:ver:u1"), eq("0"), ttl.capture());
        assertTtlCoversTokenLifetime(ttl.getValue());
    }

    /**
     * key 已存在（作废过）：只续 TTL，<b>绝不把读到的值写回去</b>。
     *
     * <p>回写会与并发的 {@code revokeAll} 形成「后写覆盖前写」：登录线程读到 1、
     * 登出线程把版本置 2、登录线程再把 1 写回去 —— 作废被无声撤销。</p>
     */
    @Test
    @DisplayName("已作废过的用户：只续 TTL、不回写版本值（不覆盖并发作废，P2-20）")
    void anchorOnlyExtendsTtlWhenKeyExists() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> v = ops(redis);
        when(v.get("tcm:ehr:auth:ver:u1")).thenReturn("2");
        when(redis.expire(anyString(), any(Duration.class))).thenReturn(true);
        JwtUtil u = newUtil(redis);

        String token = u.generateToken("u1", "alice", "用户");

        assertNotNull(u.parseAndVerify(token), "版本一致，令牌应可用");
        verify(v, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
        verify(v, never()).set(anyString(), anyString(), any(Duration.class));
    }

    /** 作废：TTL 同样带抖动，且仍严格长于令牌寿命 */
    @Test
    @DisplayName("作废令牌：TTL 长于令牌寿命且带抖动（批量登出不同秒过期，P2-20）")
    void revokeUsesJitteredTtlLongerThanTokenLifetime() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ops(redis);
        AtomicReference<String> keySeen = new AtomicReference<>();
        AtomicReference<String> ttlSeen = new AtomicReference<>();
        when(redis.execute(anyScript(), anyList(), any())).thenAnswer(inv -> {
            Object[] a = flattenVarargs(inv);
            keySeen.set(String.valueOf(((List<?>) a[1]).get(0)));
            ttlSeen.set(String.valueOf(a[2]));
            return 1L;
        });
        JwtUtil u = newUtil(redis);

        u.revokeAll("u1");

        assertEquals("tcm:ehr:auth:ver:u1", keySeen.get(), "版本 key 必须带统一前缀 tcm:ehr:");
        assertTtlCoversTokenLifetime(Duration.ofMillis(Long.parseLong(ttlSeen.get())));
    }

    /**
     * 作废走<b>原子自增</b>，不再「先读当前值再写回」。
     *
     * <p>原实现的读-改-写会在两次并发作废时丢掉一次递增量（双设备同时登出）；
     * 这里用一个 Map 充当 Redis 键空间、按脚本语义做 INCR，把「两次作废必须递增到 2」
     * 钉住，并断言<b>没有再读当前值</b> —— 那个形状正是竞态的来源。</p>
     */
    @Test
    @DisplayName("作废令牌：原子自增、不读当前值，两次作废递增到 2（P2-20）")
    void revokeIncrementsAtomicallyWithoutReadModifyWrite() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> v = ops(redis);
        Map<String, String> store = new HashMap<>();
        when(redis.execute(anyScript(), anyList(), any())).thenAnswer(inv -> {
            Object[] a = flattenVarargs(inv);
            String key = String.valueOf(((List<?>) a[1]).get(0));
            String next = String.valueOf(Long.parseLong(store.getOrDefault(key, "0")) + 1);
            store.put(key, next);
            return Long.valueOf(next);
        });
        JwtUtil u = newUtil(redis);

        u.revokeAll("u1");
        u.revokeAll("u1");

        assertEquals("2", store.get("tcm:ehr:auth:ver:u1"), "两次作废必须递增到 2");
        verify(v, never()).get(anyString());
        verify(v, never()).set(anyString(), anyString(), any(Duration.class));
    }

    /** 抖动只向上取：多次取值都落在 [24h, 25h10m]，不会低于令牌寿命 */
    @Test
    @DisplayName("版本号 TTL 抖动只向上：多次取值恒 > 令牌寿命（P2-20 不变式）")
    void versionTtlNeverShorterThanTokenLifetime() {
        JwtUtil u = newUtil(mock(StringRedisTemplate.class));
        for (int i = 0; i < 200; i++) {
            Duration ttl = ReflectionTestUtils.invokeMethod(u, "versionTtl");
            assertTtlCoversTokenLifetime(ttl);
        }
    }

    /**
     * 占位 key 存在（值 "0"）时，不含 {@code ver} 的老令牌仍应可用。
     *
     * <p>这是 P2-20 新引入的一个状态：改造前「从未作废」表现为<b>没有 key</b>，
     * 现在表现为<b>有一个值为 "0" 的 key</b>。老令牌没有 ver claim，按 "0" 比对即可 ——
     * 否则上线瞬间所有升级前签发的令牌会被集体踢下线。</p>
     */
    @Test
    @DisplayName("占位版本号 \"0\" 存在时：无 ver claim 的老令牌仍可用（P2-20 平滑升级）")
    void legacyTokenStillValidWithPlaceholderKey() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> v = ops(redis);
        when(v.get(anyString())).thenReturn("0");
        when(redis.expire(anyString(), any(Duration.class))).thenReturn(true);
        JwtUtil u = newUtil(redis);

        String legacy = io.jsonwebtoken.Jwts.builder()
                .setSubject("u1")
                .claim("username", "alice")
                .claim("role", "用户")
                .setIssuedAt(new java.util.Date())
                .setExpiration(new java.util.Date(System.currentTimeMillis() + 86400000L))
                .signWith(ReflectionTestUtils.invokeMethod(u, "getKey"),
                        io.jsonwebtoken.SignatureAlgorithm.HS256)
                .compact();

        assertNotNull(u.parseAndVerify(legacy), "占位值 \"0\" 应与「无 ver claim」等价，不能把老令牌踢下线");
    }

    /**
     * 把 {@code (script, keys, ttl)} 三个实参摊平。
     *
     * <p>{@code execute} 的第三个参数是可变参数，Mockito 各版本对
     * 「{@code getArguments()} 是否展开可变参数」的处理不一致：有的给
     * {@code [script, keys, Object[]{ttl}]}，有的给 {@code [script, keys, ttl]}。
     * 这里两种都认，避免测试因 Mockito 版本而假红（与 {@code LoginFailureLockTest} 同一范式）。</p>
     */
    private static Object[] flattenVarargs(org.mockito.invocation.InvocationOnMock inv) {
        Object[] raw = inv.getArguments();
        if (raw.length >= 3 && raw[2] instanceof Object[] tail) {
            Object[] out = new Object[raw.length - 1 + tail.length];
            System.arraycopy(raw, 0, out, 0, raw.length - 1);
            System.arraycopy(tail, 0, out, raw.length - 1, tail.length);
            return out;
        }
        return raw;
    }

    @SuppressWarnings("unchecked")
    private static RedisScript<Long> anyScript() {
        return any();
    }

    /** TTL 必须严格大于令牌寿命（24h），否则 key 先于令牌消失、计数回落会让旧令牌复活 */
    private static void assertTtlCoversTokenLifetime(Duration ttl) {
        assertNotNull(ttl, "TTL 不应为空");
        assertTrue(ttl.compareTo(Duration.ofHours(24)) > 0,
                "TTL 必须严格大于令牌寿命（24h），实际 " + ttl);
        assertTrue(ttl.compareTo(Duration.ofHours(25).plusMinutes(10)) <= 0,
                "抖动上限 10 分钟，实际 " + ttl);
    }
}
