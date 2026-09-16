package com.mall.infra.redis;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Redis-only operations for coupon inventory.
 *
 * <p>Business validation remains in the server coupon service.  This class
 * only initializes and atomically changes the inventory counter.</p>
 */
@Component
@RequiredArgsConstructor
public class CouponStockRedisService {

    private final RedisService redisService;

    /**
     * Lazily initialize the counter from the database snapshot and reserve
     * one unit.  SETNX makes initialization safe when several API instances
     * receive the first claim concurrently.
     */
    public boolean reserve(Long couponId, int total, long claimed) {
        long initialStock = Math.max(0L, (long) total - claimed);
        String key = CouponKey.stockKey(couponId);
        redisService.setIfAbsent(key, String.valueOf(initialStock));

        Long remaining = redisService.decr(key);
        if (remaining == null) {
            return false;
        }
        if (remaining < 0) {
            // Keep the counter at zero after a rejected concurrent decrement.
            redisService.incr(key);
            return false;
        }
        return true;
    }

    public void rollback(Long couponId) {
        redisService.incr(CouponKey.stockKey(couponId));
    }

    public Long getStock(Long couponId) {
        return redisService.getValue(CouponKey.stockKey(couponId), Long.class);
    }
}
