package com.mall.security.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class JwtUtil {

    // JWT 签名密钥，实际项目应放在配置文件里
    private static final String SECRET = "YourSuperLongSecretKeyForJWT2024!!";

    // Token 有效期
    private static final long ACCESS_EXPIRE = 2 * 60 * 60 * 1000L;       // 2 小时
    private static final long REFRESH_EXPIRE = 7 * 24 * 60 * 60 * 1000L; // 7 天

    // 把字符串密钥转成 HMAC-SHA 密钥对象
    private static SecretKey getKey() {
        return Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    }

    // 生成短期访问令牌（调用接口用）
    public static String generateAccessToken(Long userId) {
        return buildToken(userId, ACCESS_EXPIRE);
    }

    // 生成长期刷新令牌（过期后换新的 access token）
    public static String generateRefreshToken(Long userId) {
        return buildToken(userId, REFRESH_EXPIRE);
    }

    // 核心：构建 JWT，把 userId 写进 subject，设置签发时间和过期时间
    private static String buildToken(Long userId, long expire) {
        return Jwts.builder()
                .subject(String.valueOf(userId))                          // 把 userId 作为令牌主体
                .issuedAt(new Date())                                     // 签发时间
                .expiration(new Date(System.currentTimeMillis() + expire))// 过期时间
                .signWith(getKey())                                       // 签名，防篡改
                .compact();                                               // 输出为字符串
    }

    // 从 Token 中解析出 userId
    public static Long getUserId(String token) {
        Claims claims = parseToken(token);
        return Long.valueOf(claims.getSubject());
    }

    // 判断 Token 是否过期
    public static boolean isExpired(String token) {
        try {
            parseToken(token);    // 解析成功 = 没过期
            return false;
        } catch (Exception e) {
            return true;          // 解析失败 = 已过期或无效
        }
    }

    // 校验 Token 是否有效
    public static boolean validate(String token) {
        return !isExpired(token);
    }

    // 底层：解析 JWT，验证签名并提取载荷
    private static Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(getKey())       // 用同一个密钥验证签名
                .build()
                .parseSignedClaims(token)   // 解析（签名不对会直接抛异常）
                .getPayload();              // 拿到载荷 { sub, iat, exp }
    }
}
