package com.mall.infra.redis;

/**
 * 订单域 Redis Key 定义。
 *
 * Key 格式：mall:order:<purpose>:{业务ID}
 *
 * 使用示例：
 *   redisService.exists(OrderKey.seckillOrder, userId + ":" + itemId); // 判断是否已秒杀过
 */
public class OrderKey extends BasePrefix {

    /**
     * 秒杀订单缓存（按用户ID + 秒杀商品ID 查询）。
     * 用于秒杀下单时快速判断"一人一单"——该用户是否已购买过此秒杀商品，
     * 比查 seckill_order 表的 uk_user_seckill 唯一索引快，且减少 DB 压力。
     * 永不过期，数据写入后一直保留。
     * → Key: mall:order:seckill:{userId}:{itemId}
     */
    public static final OrderKey seckillOrder = new OrderKey("mall:order:seckill:");

    private OrderKey(String prefix) {
        super(prefix);
    }
}
