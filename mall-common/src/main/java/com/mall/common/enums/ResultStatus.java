package com.mall.common.enums;

import lombok.Getter;

@Getter
public enum ResultStatus {

    // ==================== 通用 ====================
    SUCCESS(0, "成功"),
    EXCEPTION(-1, "系统异常"),
    PARAM_ERROR(10000, "参数错误"),
    SYSTEM_ERROR(10001, "系统错误"),
    METHOD_NOT_ALLOWED(10002, "请求方法不支持"),
    DATA_ALREADY_EXIST(10008, "数据已经存在"),
    DATA_NOT_FOUND(10009, "数据不存在"),

    // ==================== 用户模块 20000 ====================
    USER_BANNED(20000, "用户被禁用"),
    USER_NOT_EXIST(20001, "用户不存在"),
    PASSWORD_ERROR(20002, "密码错误"),
    MOBILE_ERROR(20003, "手机号已存在"),
    ADDRESS_NOT_EXIST(20004, "地址不存在"),

    // ==================== 订单模块 50000 ====================
    ORDER_NOT_EXIST(50000, "订单不存在"),
    ORDER_STATUS_ERROR(50001, "订单状态不允许此操作"),

    // ==================== 秒杀模块 60000 ====================
    SECKILL_END(60000, "商品已经秒杀完毕"),
    SECKILL_REPEAT(60001, "不能重复秒杀"),
    SECKILL_FAIL(60002, "秒杀失败"),

    // ==================== 优惠券模块 70000 ====================
    COUPON_NOT_EXIST(70000, "优惠券不存在"),
    COUPON_EXPIRED(70001, "优惠券已过期"),
    COUPON_STOCK_EMPTY(70002, "优惠券已领完"),
    COUPON_ALREADY_RECEIVED(70003, "优惠券已经领取"),
    COUPON_NOT_OWNED(70004, "未拥有该优惠券"),
    COUPON_ALREADY_USED(70005, "优惠券已经使用"),
    COUPON_NOT_APPLICABLE(70006, "优惠券不满足使用条件"),
    COUPON_STATUS_ERROR(70007, "优惠券状态不允许此操作"),

    // ==================== 支付物流模块 80000 ====================
    PAYMENT_NOT_EXIST(80000, "支付记录不存在"),
    PAYMENT_STATUS_ERROR(80001, "支付状态不允许此操作"),
    LOGISTICS_NOT_EXIST(80002, "物流记录不存在"),
    LOGISTICS_STATUS_ERROR(80003, "物流状态不允许此操作"),

    // ==================== 商家模块 90000 ====================
    MERCHANT_PRODUCT_FORBIDDEN(90000, "无权操作该商品"),
    MERCHANT_ORDER_FORBIDDEN(90001, "无权操作该订单"),
    MERCHANT_LOGIN_FAILED(90002, "商家账号或密码错误"),
    MERCHANT_NOT_BOUND(90003, "该账号未绑定商家，不能登录商家端"),
    MERCHANT_DISABLED(90004, "商家已被禁用"),
    ADMIN_LOGIN_FAILED(90005, "管理员账号或密码错误"),
    ADMIN_NOT_SUPER(90006, "该账号不是平台管理员"),
    ADMIN_USER_NOT_EXIST(90007, "用户不存在");

    private final int code;
    private final String message;

    ResultStatus(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public String getName() {
        return this.name();
    }

    public String toString() {
        return this.getName();
    }

}
