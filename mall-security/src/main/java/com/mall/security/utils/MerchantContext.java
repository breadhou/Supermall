package com.mall.security.utils;

/**
 * 当前请求的商家上下文。与 C 端的 {@link UserContext} 完全独立。
 *
 * <p>商家端的 token 由独立密钥签发、经独立过滤器解析，因此这里存的是
 * admin_user 的 id 与它所属的 merchant_id。业务代码通过
 * {@link #getMerchantId()} 做归属隔离。</p>
 */
public class MerchantContext {

    private static final ThreadLocal<Long> ADMIN_ID_HOLDER = new ThreadLocal<>();
    private static final ThreadLocal<Long> MERCHANT_ID_HOLDER = new ThreadLocal<>();

    public static void set(Long adminUserId, Long merchantId) {
        ADMIN_ID_HOLDER.set(adminUserId);
        MERCHANT_ID_HOLDER.set(merchantId);
    }

    /** 当前登录的商家账号 id（admin_user.id）。 */
    public static Long getAdminUserId() {
        return ADMIN_ID_HOLDER.get();
    }

    /** 当前账号所属商家 id，归属隔离的依据。 */
    public static Long getMerchantId() {
        return MERCHANT_ID_HOLDER.get();
    }

    public static void clear() {
        ADMIN_ID_HOLDER.remove();
        MERCHANT_ID_HOLDER.remove();
    }
}
