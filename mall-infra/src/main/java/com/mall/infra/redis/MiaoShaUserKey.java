package com.mall.infra.redis;

/**
 * 用户模块 Redis Key 定义。
 * token: 登录 Token，TTL 2天；nickName: 昵称缓存，永不过期。
 */
public class MiaoShaUserKey extends BasePrefix {
    public static final int TOKEN_EXPIRE = 3600 * 24 * 2;
    public static MiaoShaUserKey token = new MiaoShaUserKey(TOKEN_EXPIRE, "tk");
    public static MiaoShaUserKey getByNickName = new MiaoShaUserKey(0, "nickName");

    public MiaoShaUserKey(int expireSeconds, String prefix) {
        super(expireSeconds, prefix);
    }
}
