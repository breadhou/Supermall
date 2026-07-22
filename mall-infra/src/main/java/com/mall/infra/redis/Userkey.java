package com.mall.infra.redis;

/**
 * 用户模块 Redis Key 定义（v1 风格），永不过期。
 * id: 按 ID 查用户；name: 按用户名查用户。
 */
public class Userkey extends BasePrefix {

    public static Userkey getById = new Userkey("id");
    public static Userkey getByName = new Userkey("name");

    private Userkey(String prefix) {
        super(prefix);
    }

}
