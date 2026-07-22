package com.mall.security.utils;

/**
 * 当前请求的用户上下文。
 * 基于 ThreadLocal，每个请求线程独立，请求结束自动清除。
 *
 * 使用方式：在 Controller 或 Service 中直接调用 UserContext.getUserId()
 */
public class UserContext {

    // ThreadLocal：每个线程有自己独立的副本，线程间互不干扰
    private static final ThreadLocal<Long> USER_HOLDER = new ThreadLocal<>();

    // 请求进来时，JwtAuthFilter 调用此方法存入当前用户 ID
    public static void setUserId(Long userId) {
        USER_HOLDER.set(userId);
    }

    // 业务代码通过此方法获取当前用户 ID，不需要从 Controller 一层层传参
    public static Long getUserId() {
        return USER_HOLDER.get();
    }

    // 请求结束后必须清除，防止内存泄漏（线程池复用场景下尤其重要）
    public static void clear() {
        USER_HOLDER.remove();
    }
}
