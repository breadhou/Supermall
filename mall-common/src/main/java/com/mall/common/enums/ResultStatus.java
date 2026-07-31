package com.mall.common.enums;

import lombok.Getter;

@Getter
public enum ResultStatus {

    // ==================== 通用 ====================
    SUCCESS(0, "成功"),
    EXCEPTION(-1, "系统异常"),
    PARAM_ERROR(10000, "参数错误"),
    SYSTEM_ERROR(10001, "系统错误"),
    DATA_ALREADY_EXIST(10008, "数据已经存在"),
    DATA_NOT_FOUND(10009, "数据不存在"),

    // ==================== 用户模块 20000 ====================
    USER_BANNED(20000, "用户被禁用"),
    USER_NOT_EXIST(20001, "用户不存在"),
    PASSWORD_ERROR(20002, "密码错误"),
    MOBILE_ERROR(20003, "手机号已存在"),

    // ==================== 订单模块 50000 ====================
    ORDER_NOT_EXIST(50000, "订单不存在"),
    ORDER_STATUS_ERROR(50001, "订单状态不允许此操作"),

    // ==================== 秒杀模块 60000 ====================
    SECKILL_END(60000, "商品已经秒杀完毕"),
    SECKILL_REPEAT(60001, "不能重复秒杀"),
    SECKILL_FAIL(60002, "秒杀失败");

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
