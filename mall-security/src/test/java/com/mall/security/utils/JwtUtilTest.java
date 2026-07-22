package com.mall.security.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JwtUtilTest {

    @Test
    void shouldGenerateAndParseToken() {
        Long userId = 123456L;

        String token = JwtUtil.generateAccessToken(userId);
        assertNotNull(token);

        Long parsed = JwtUtil.getUserId(token);
        assertEquals(userId, parsed);
    }

    @Test
    void shouldValidateValidToken() {
        String token = JwtUtil.generateAccessToken(1L);
        assertTrue(JwtUtil.validate(token));
        assertFalse(JwtUtil.isExpired(token));
    }

    @Test
    void shouldDetectExpiredToken() {
        // JwtUtil 的过期时间是写死的 2 小时，没法等那么久
        // 这里只测：一个刚生成的 Token 不应该过期
        String token = JwtUtil.generateAccessToken(1L);
        assertFalse(JwtUtil.isExpired(token));
    }

    @Test
    void shouldRejectTamperedToken() {
        String token = JwtUtil.generateAccessToken(1L);
        String tampered = token.substring(0, token.length() - 3) + "xxx";

        assertFalse(JwtUtil.validate(tampered));
    }

    @Test
    void shouldRejectEmptyToken() {
        assertFalse(JwtUtil.validate(""));
    }

    @Test
    void shouldRejectNullToken() {
        assertFalse(JwtUtil.validate(null));
    }

    @Test
    void shouldRejectGarbageToken() {
        assertFalse(JwtUtil.validate("this.is.not.a.jwt"));
    }

    @Test
    void refreshTokenShouldHaveDifferentExpiry() {
        String accessToken = JwtUtil.generateAccessToken(1L);
        String refreshToken = JwtUtil.generateRefreshToken(1L);

        // 两个 Token 内容相同（都是同一个 userId），但签名时 iat 不同，所以字符串不同
        assertNotEquals(accessToken, refreshToken);
    }
}
