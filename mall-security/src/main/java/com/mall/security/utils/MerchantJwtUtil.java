package com.mall.security.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * 商家端 JWT。与 C 端的 {@link JwtUtil} 刻意使用**不同的密钥**。
 *
 * <p>密钥不同意味着隔离发生在签名校验层面：C 端 token 不是「被某个判断拦住」，
 * 而是根本无法通过商家端的签名验证。反方向同理。这是本项目选择「独立一套」
 * 而非「同一套 + 角色声明」的全部理由。</p>
 *
 * <p>密钥必须由环境变量注入，仓库中不提供默认值——缺失时应用启动即失败。</p>
 */
@Component
public class MerchantJwtUtil {

    /** HMAC-SHA256 要求密钥至少 256 位。 */
    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKey key;
    private final long accessExpireMs;

    public MerchantJwtUtil(@Value("${mall.merchant.jwt-secret}") String secret,
                           @Value("${mall.merchant.access-expire-ms:7200000}") long accessExpireMs) {
        byte[] raw = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (raw.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "mall.merchant.jwt-secret must be at least " + MIN_SECRET_BYTES
                            + " bytes for HMAC-SHA256; set the MERCHANT_JWT_SECRET environment variable");
        }
        this.key = Keys.hmacShaKeyFor(raw);
        this.accessExpireMs = accessExpireMs;
    }

    public String generateToken(Long adminUserId, Long merchantId, String role) {
        return Jwts.builder()
                .subject(String.valueOf(adminUserId))
                .claim("merchantId", merchantId)
                .claim("role", role)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + accessExpireMs))
                .signWith(key)
                .compact();
    }

    /** 签名错误或已过期均返回 false。 */
    public boolean validate(String token) {
        try {
            parse(token);
            return true;
        } catch (Exception exception) {
            return false;
        }
    }

    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
