package com.tcm.ehr.common.utils;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
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
        assertTrue(u.isValid(u.generateToken("u1", "alice", "用户")));
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
        assertTrue(u.isValid(token));

        // 退出登录：Redis 里的当前版本变成 "1"
        when(v.get("tcm:auth:ver:u1")).thenReturn("1");
        assertFalse(u.isValid(token), "版本不一致的令牌必须失效");
    }

    @Test
    @DisplayName("版本一致时仍有效（改密码后重新登录签发的新令牌能用）")
    void matchingVersionStaysValid() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> v = ops(redis);
        when(v.get("tcm:auth:ver:u1")).thenReturn("3");
        JwtUtil u = newUtil(redis);
        // 新令牌带 ver=3（currentVersion 读到 3）
        String token = u.generateToken("u1", "alice", "用户");
        assertTrue(u.isValid(token));
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
        when(v.get("tcm:auth:ver:uA")).thenReturn("1");
        when(v.get("tcm:auth:ver:uB")).thenReturn(null);

        assertFalse(u.isValid(tokenA), "A 已登出");
        assertTrue(u.isValid(tokenB), "B 不该被牵连");
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
        assertTrue(u.isValid(token), "Redis 故障时应降级放行，保证可用性");
    }

    @Test
    @DisplayName("篡改签名 / 非法令牌的令牌仍无效（版本机制不放松既有校验）")
    void tamperedTokenStillInvalid() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> v = ops(redis);
        when(v.get(anyString())).thenReturn(null);
        JwtUtil u = newUtil(redis);
        assertFalse(u.isValid("not-a-jwt"), "非法令牌必须无效");
        String token = u.generateToken("u1", "alice", "用户");
        assertFalse(u.isValid(token.substring(0, token.length() - 2) + "xx"), "被篡改的令牌必须无效");
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
        assertTrue(u.isValid(legacy), "尚未发生作废时，老令牌应继续可用");
    }
}
