package com.mall.infra.redis;

/**
 * 商品模块 Redis Key 定义。
 * gl: 商品列表缓存，60s；gd: 商品详情缓存，60s；gs: 秒杀库存，永不过期。
 */
public class GoodsKey extends BasePrefix {

    public static GoodsKey getGoodsList = new GoodsKey(60, "gl");
    public static GoodsKey getGoodsDetail = new GoodsKey(60, "gd");
    public static GoodsKey getMiaoshaGoodsStock = new GoodsKey(0, "gs");
    private GoodsKey(int expireSeconds, String prefix) {
        super(expireSeconds, prefix);
    }

}
