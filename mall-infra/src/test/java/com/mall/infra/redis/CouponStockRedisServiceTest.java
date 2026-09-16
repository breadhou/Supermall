package com.mall.infra.redis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CouponStockRedisServiceTest {

    @Mock
    private RedisService redisService;

    @Test
    void reserve_shouldInitializeAndDecrementCompleteClusterSafeKey() {
        Long couponId = 2001L;
        when(redisService.decr(CouponKey.stockKey(couponId))).thenReturn(4L);

        CouponStockRedisService service = new CouponStockRedisService(redisService);

        assertTrue(service.reserve(couponId, 5, 0));

        verify(redisService).setIfAbsent(CouponKey.stockKey(couponId), "5");
        verify(redisService).decr(CouponKey.stockKey(couponId));
    }

    @Test
    void reserve_shouldRollbackRejectedNegativeDecrement() {
        Long couponId = 2001L;
        when(redisService.decr(CouponKey.stockKey(couponId))).thenReturn(-1L);

        CouponStockRedisService service = new CouponStockRedisService(redisService);

        assertFalse(service.reserve(couponId, 1, 1));

        verify(redisService).incr(CouponKey.stockKey(couponId));
    }
}
