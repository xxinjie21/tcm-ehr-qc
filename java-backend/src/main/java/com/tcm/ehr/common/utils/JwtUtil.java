package com.tcm.ehr.common.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;
import java.util.concurrent.TimeUnit;

/**
 * JWT 令牌工具：登录时签发，请求校验时解析出用户身份与角色。
 *
 * <p><b>令牌失效机制（批次 15）</b>：JWT 本身无状态、签发后 24h 内一直有效，
 * 改密码或退出登录都不会让已发出去的令牌立刻作废。这里给每个用户维护一个
 * <b>令牌版本号</b>（{@code tcm:auth:ver:{userId}}，存 Redis）：签发时把当前版本写进
 * 令牌的 {@code ver} claim，请求时比对——版本对不上即视为失效。</p>
 *
 * <p>用版本号而不是「黑名单存 jti」：黑名单只增不减、Redis 内存无界；版本号
 * 每个用户只有一个整数，退出登录 / 改密码时 {@code INCR} 一次就作废该用户<b>全部</b>旧令牌。</p>
 *
 * <p><b>可用性取舍（与 JwtInterceptor 对组织解析同一口径）</b>：Redis 不可用时
 * 校验降级为「只看签名与有效期」，不抛异常——一次 Redis 抖动不该把全站打成 500。</p>
 */
@Component
public class JwtUtil {

    /** 令牌版本号的 Redis key 前缀 */
    static final String VER_KEY = "tcm:auth:ver:";

    /** 签名密钥（HS256 要求至少 32 字节） */
    @Value("${jwt.secret}")
    private String secret;

    /** 令牌有效期，默认 24 小时 */
    @Value("${jwt.expire-hours:24}")
    private long expireHours;

    private final StringRedisTemplate redis;

    public JwtUtil(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 由配置密钥派生 HS256 签名 Key */
    private Key getKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 签发令牌。
     *
     * @param userId 用户 ID，写入 subject
     * @param username 用户名，写入 username claim
     * @param role 角色，写入 role claim（鉴权时据此判定权限）
     * @return 签名后的 JWT 字符串
     */
    public String generateToken(String userId, String username, String role) {
        Date now = new Date();
        // 1. 过期时间 = 当前时间 + 配置的有效期
        Date expiry = new Date(now.getTime() + expireHours * 3600 * 1000);
        // 2. 装载身份信息后签名；ver claim 让「退出登录 / 改密码」能作废旧令牌
        return Jwts.builder()
                .setSubject(userId)
                .claim("username", username)
                .claim("role", role)
                .claim("ver", currentVersion(userId))
                .setIssuedAt(now)
                .setExpiration(expiry)
                .signWith(getKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * 解析令牌并校验签名与有效期，取出载荷。
     *
     * @param token JWT 字符串
     * @return 令牌载荷（含 subject / username / role / ver）
     */
    public Claims parseToken(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(getKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    /**
     * 判断令牌是否可用（签名 + 有效期 + 令牌版本）。
     *
     * @param token JWT 字符串
     * @return 可用为 true
     */
    public boolean isValid(String token) {
        try {
            Claims claims = parseToken(token);
            // 令牌版本与当前版本不一致 = 已被退出登录 / 改密码作废
            return versionMatches(claims.getSubject(), claims.get("ver"));
        } catch (Exception e) {
            // 过期 / 签名不符 / 格式非法一律视为不可用；此处只给布尔结果，由拦截器统一回 401
            return false;
        }
    }

    // ------------------------------------------------------------ 令牌版本

    /**
     * 取用户当前令牌版本；无记录（从未作废过）时返回 "0"。
     *
     * <p>Redis 不可用返回 {@code "0"}：此时签发的令牌带 ver=0，而校验也降级为
     * 「不比对版本」，自洽。见 {@link #versionMatches}。</p>
     */
    String currentVersion(String userId) {
        try {
            String v = redis.opsForValue().get(VER_KEY + userId);
            return v == null ? "0" : v;
        } catch (Exception e) {
            return "0";
        }
    }

    /**
     * 作废该用户全部已签发的令牌：令牌版本 +1。
     *
     * <p>退出登录与改密码都调它——两者语义一致：让这个用户的旧令牌立刻失效。</p>
     */
    public void revokeAll(String userId) {
        try {
            String key = VER_KEY + userId;
            String cur = redis.opsForValue().get(key);
            long next = (cur == null ? 0L : Long.parseLong(cur)) + 1;
            redis.opsForValue().set(key, String.valueOf(next), expireHours + 1, TimeUnit.HOURS);
        } catch (Exception e) {
            // Redis 不可用时无法作废；令牌仍在有效期内可继续用。降级记录，不抛异常阻断登出。
            org.slf4j.LoggerFactory.getLogger(JwtUtil.class)
                    .warn("[Auth] Redis 不可用，令牌作废失败 userId={}: {}", userId, e.getMessage());
        }
    }

    /**
     * 令牌里的版本是否等于当前版本。
     *
     * <p>Redis 不可用时返回 {@code true}（降级放行）：否则一次 Redis 抖动会让
     * 全站所有已登录用户瞬间被踢下线。安全与可用性在此明确取舍为「可用优先」。</p>
     */
    boolean versionMatches(String userId, Object verClaim) {
        try {
            String current = redis.opsForValue().get(VER_KEY + userId);
            if (current == null) {
                // 从未作废过：任何带 ver 的令牌都应视为有效
                return true;
            }
            String tokenVer = verClaim == null ? "0" : String.valueOf(verClaim);
            return current.equals(tokenVer);
        } catch (Exception e) {
            return true;
        }
    }
}
