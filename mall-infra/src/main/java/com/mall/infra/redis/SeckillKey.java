package com.mall.infra.redis;

/**
 * 秒杀域 Redis Key 定义，对应设计文档 7.3 节。
 *
 * Key 格式：mall:seckill:<purpose>:{业务ID}
 *
 * 使用示例：
 *   // 预热库存
 *   redisService.set(SeckillKey.stock, itemId.toString(), stockCount);
 *   // Lua 脚本原子扣减后检查库存
 *   Long stock = redisService.get(SeckillKey.stock, itemId, Long.class);
 *   // 写秒杀结果
 *   redisService.set(SeckillKey.result, itemId + ":" + userId, "1");
 *   // 查询秒杀结果
 *   String result = redisService.get(SeckillKey.result.getPrefix() + itemId + ":" + userId);
 */
public class SeckillKey extends BasePrefix {

    // ==================== 设计文档 7.3 节要求的 4 个核心 Key ====================

    /**
     * 秒杀商品预热库存（String number）。
     * 活动开始前从 seckill_item.stock 加载到 Redis，
     * 秒杀时通过 Lua 脚本原子 DECR，永不过期。
     * → Key: mall:seckill:stock:{itemId}
     */
    public static final SeckillKey stock = new SeckillKey(0, "mall:seckill:stock:");

    /**
     * 秒杀结果缓存。
     * 0 = 排队中（消息已入队，等待消费者处理）
     * 1 = 秒杀成功（已生成订单）
     * -1 = 秒杀失败（库存不足或消费异常）
     * → Key: mall:seckill:result:{itemId}:{userId}
     */
    public static final SeckillKey result = new SeckillKey(3600, "mall:seckill:result:");

    /**
     * 用户已购数量，用于限制每人购买件数（limit_per_user）。
     * 每次秒杀成功后 INCR，和 limit_per_user 比较。
     * → Key: mall:seckill:limit:{itemId}:{userId}
     */
    public static final SeckillKey userLimit = new SeckillKey(0, "mall:seckill:limit:");

    /**
     * 用户下单分布式锁，防止同一用户对同一秒杀商品重复提交请求。
     * 10 秒后自动释放，配合 RedisLock 使用。
     * → Key: mall:seckill:lock:{itemId}:{userId}
     */
    public static final SeckillKey userLock = new SeckillKey(10, "mall:seckill:lock:");

    // ==================== 秒杀辅助 Key ====================

    /**
     * 秒杀地址隐藏（接口防刷）。
     * 秒杀开始前不暴露真实下单 URL，用户先请求获取动态 path，
     * 带上正确 path 才能访问秒杀接口，60 秒有效。
     * → Key: mall:seckill:path:{itemId}:{userId}
     */
    public static final SeckillKey path = new SeckillKey(60, "mall:seckill:path:");

    /**
     * 秒杀数学验证码答案。
     * 用户提交秒杀请求时需一并带上验证码，300 秒有效。
     * → Key: mall:seckill:verify:{itemId}:{userId}
     */
    public static final SeckillKey verifyCode = new SeckillKey(300, "mall:seckill:verify:");

    private SeckillKey(int expireSeconds, String prefix) {
        super(expireSeconds, prefix);
    }
}
