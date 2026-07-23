package com.mall.infra.redis;

/**
 * 商品域 Redis Key 定义。
 *
 * Key 格式：mall:product:<purpose>:{业务ID}
 *
 * 商品数据读多写少，适合用缓存扛读压力。过期时间设短一些，
 * 数据变更后最多等 60 秒就能被用户看到，无需手动清缓存。
 *
 * 使用示例：
 *   redisService.set(ProductKey.list, pageKey, productPage);    // 缓存商品列表
 *   redisService.get(ProductKey.detail, productId, ProductVO.class); // 读商品详情
 */
public class ProductKey extends BasePrefix {

    /** 商品列表分页缓存，60 秒过期 */
    public static final ProductKey list = new ProductKey(60, "mall:product:list:");

    /** 商品详情缓存（含 SKU 列表），60 秒过期 */
    public static final ProductKey detail = new ProductKey(60, "mall:product:detail:");

    private ProductKey(int expireSeconds, String prefix) {
        super(expireSeconds, prefix);
    }
}
