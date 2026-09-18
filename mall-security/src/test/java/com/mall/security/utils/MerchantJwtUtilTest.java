package com.mall.security.utils;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 商家端 JWT。最关键的一条：C 端 token 必须在签名层面就被拒绝，
 * 而不是靠某个业务判断拦住。
 */
class MerchantJwtUtilTest {

    private static final String MERCHANT_SECRET = "merchant-side-secret-key-for-tests-0123456789";

    private final MerchantJwtUtil merchantJwtUtil = new MerchantJwtUtil(MERCHANT_SECRET, 7_200_000L);

    @Test
    void roundTrip_shouldCarryMerchantBinding() {
        String token = merchantJwtUtil.generateToken(1001L, 5001L, "ADMIN");

        assertTrue(merchantJwtUtil.validate(token));
        Claims claims = merchantJwtUtil.parse(token);
        assertEquals("1001", claims.getSubject());
        assertEquals(5001L, claims.get("merchantId", Long.class));
        assertEquals("ADMIN", claims.get("role", String.class));
    }

    @Test
    void validate_shouldRejectTokenSignedWithTheCEndSecret() {
        // C 端 JwtUtil 用另一个密钥签发；商家端必须无法接受它
        String cEndToken = JwtUtil.generateAccessToken(1001L);

        assertFalse(merchantJwtUtil.validate(cEndToken));
    }

    @Test
    void validate_shouldRejectGarbage() {
        assertFalse(merchantJwtUtil.validate("not-a-token"));
    }

    @Test
    void constructor_shouldRejectTooShortSecret() {
        assertThrows(IllegalStateException.class, () -> new MerchantJwtUtil("too-short", 1000L));
    }
}
