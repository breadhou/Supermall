package com.mall.infra.redis;

/**
 * 用户域 Redis Key 定义。
 *
 * Key 格式：mall:user:<purpose>:{业务ID}
 *
 * 使用示例：
 *   redisService.set(UserKey.id, userId.toString(), user);             // 缓存用户信息
 *   redisService.set(UserKey.accessToken, userId.toString(), token);   // 缓存 access_token
 */
public class UserKey extends BasePrefix {

    // ==================== 用户信息缓存 ====================

    /** 按用户ID缓存用户信息，永不过期（用户信息变更时主动更新） */
    public static final UserKey id = new UserKey("mall:user:id:");

    /** 按用户名缓存用户信息，永不过期 */
    public static final UserKey name = new UserKey("mall:user:name:");

    // ==================== JWT Token 缓存 ====================

    /**
     * 短期访问令牌，有效期 2 小时。
     * 用于接口鉴权，过期后用 refreshToken 换新，无需重新登录。
     */
    public static final UserKey accessToken = new UserKey(2 * 3600, "mall:user:token:access:");

    /**
     * 长期刷新令牌，有效期 7 天。
     * 用于在 accessToken 过期后换取新的 accessToken，过期则需重新登录。
     */
    public static final UserKey refreshToken = new UserKey(7 * 24 * 3600, "mall:user:token:refresh:");

    private UserKey(int expireSeconds, String prefix) {
        super(expireSeconds, prefix);
    }

    private UserKey(String prefix) {
        super(prefix);
    }
}
