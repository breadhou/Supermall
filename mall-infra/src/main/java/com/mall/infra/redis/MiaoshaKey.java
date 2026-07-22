package com.mall.infra.redis;

/**
 * 秒杀模块 Redis Key 定义。
 * go: 商品是否秒杀完；mp: 秒杀路径隐藏，60s；vc: 验证码，300s；register: 注册验证码，300s。
 */
public class MiaoshaKey extends BasePrefix {

    public static MiaoshaKey isGoodsOver = new MiaoshaKey(0, "go");
    public static MiaoshaKey getMiaoshaPath = new MiaoshaKey(60, "mp");
    public static MiaoshaKey getMiaoshaVerifyCode = new MiaoshaKey(300, "vc");
    public static MiaoshaKey getMiaoshaVerifyCodeRegister = new MiaoshaKey(300, "register");
    private MiaoshaKey(int expireSeconds, String prefix) {
        super(expireSeconds, prefix);
    }

}
