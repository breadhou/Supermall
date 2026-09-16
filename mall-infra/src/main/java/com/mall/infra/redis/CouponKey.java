package com.mall.infra.redis;

/**
 * Redis keys used by the coupon inventory snapshot.
 *
 * <p>The coupon id is wrapped in a hash tag so all keys for one coupon stay
 * on the same Redis Cluster slot.</p>
 */
public final class CouponKey {

    private static final String ROOT = "mall:coupon:";

    private CouponKey() {
    }

    public static String stockKey(Long couponId) {
        if (couponId == null) {
            throw new IllegalArgumentException("couponId must not be null");
        }
        return ROOT + "{" + couponId + "}:stock";
    }
}
