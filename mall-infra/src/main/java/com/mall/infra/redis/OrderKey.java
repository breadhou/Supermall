package com.mall.infra.redis;

/**
 * 订单模块 Redis Key 定义。
 * moug: 根据用户ID和商品ID查询秒杀订单，永不过期。
 */
public class OrderKey extends BasePrefix {

    public static OrderKey getMiaoshaOrderByUidGid = new OrderKey("moug");


    public OrderKey(String prefix) {
        super(prefix);
    }
}
