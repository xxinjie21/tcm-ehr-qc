package com.tcm.ehr.common.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;

/**
 * JWT 令牌工具：登录时签发，请求校验时解析出用户身份与角色。
 */
@Component
public class JwtUtil {

    /** 签名密钥（HS256 要求至少 32 字节） */
    @Value("${jwt.secret}")
    private String secret;

    /** 令牌有效期，默认 24 小时 */
    @Value("${jwt.expire-hours:24}")
    private long expireHours;

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
        // 2. 装载身份信息后签名
        return Jwts.builder()
                .setSubject(userId)
                .claim("username", username)
                .claim("role", role)
                .setIssuedAt(now)
                .setExpiration(expiry)
                .signWith(getKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * 解析令牌并校验签名与有效期，取出载荷。
     *
     * @param token JWT 字符串
     * @return 令牌载荷（含 subject / username / role）
     */
    public Claims parseToken(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(getKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    /**
     * 判断令牌是否可用。
     *
     * @param token JWT 字符串
     * @return 可解析且在有效期内为 true
     */
    public boolean isValid(String token) {
        try {
            parseToken(token);
            return true;
        } catch (Exception e) {
            // 过期 / 签名不符 / 格式非法一律视为不可用；此处只给布尔结果，由拦截器统一回 401
            return false;
        }
    }
}
