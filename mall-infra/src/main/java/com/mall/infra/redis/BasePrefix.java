package com.mall.infra.redis;

/**
 * Redis Key 前缀抽象基类。
 *
 * 设计意图：每个子类代表一个业务域（如 UserKey / SeckillKey），通过静态字段暴露该域下的
 * 具体 Key 定义。RedisService 接受 KeyPrefix 后自动拼接前缀和业务 Key，避免各模块
 * 手写 Redis Key 字符串导致散落难管理。
 *
 * Key 命名规范：mall:<domain>:<purpose>:
 *   例如 mall:user:id:        → 用户信息缓存，按用户ID
 *   例如 mall:seckill:stock:  → 秒杀库存预热
 *   例如 mall:product:list:   → 商品列表缓存
 *
 * expireSeconds = 0 表示永不过期（需要业务侧在数据变更时主动删除或更新）。
 *
 * 使用示例：
 *   redisService.set(UserKey.id, "123", user);              // → 实际 Key: mall:user:id:123
 *   redisService.get(SeckillKey.stock, "456", Long.class);   // → 实际 Key: mall:seckill:stock:456
 */
public abstract class BasePrefix implements KeyPrefix {

    /** 过期时间（秒），0 表示永不过期 */
    private final int expireSeconds;

    /** Key 前缀，形如 mall:user:id: */
    private final String prefix;

    public BasePrefix(int expireSeconds, String prefix) {
        this.expireSeconds = expireSeconds;
        this.prefix = prefix;
    }

    /** 便捷构造：永不过期的 Key */
    public BasePrefix(String prefix) {
        this(0, prefix);
    }

    @Override
    public int expireSeconds() {
        return expireSeconds;
    }

    @Override
    public String getPrefix() {
        return prefix;
    }
}
