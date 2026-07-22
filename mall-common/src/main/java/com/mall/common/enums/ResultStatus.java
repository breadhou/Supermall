package com.mall.common.enums;

import lombok.Getter;

@Getter
public enum ResultStatus {

    SUCCESS(0, "成功"),
    FAIL(-1, "失败"),
    EXCEPTION(-1, "系统异常"),
    PARAM_ERROR(10000, "参数错误"),
    SYSTEM_ERROR(10001, "系统错误"),
    FILE_NOT_EXIST(10002, "文件不存在"),
    DATA_ALREADY_EXIST(10008, "数据已经存在"),

    /**
     * 注册登录
     */
    REGISTER_SUCCESS(20000, "注册成功!"),
    REGISTER_FAIL(200001, "注册失败!"),
    VERIFY_FAIL(200002, "验证码不一致!"),

    /**
     * check
     */
    BIND_ERROR(30001, "参数校验异常：%s"),
    ACCESS_LIMIT_REACHED(30002, "请求非法!"),
    REQUEST_ILLEGAL(30004, "访问太频繁!"),
    SESSION_ERROR(30005, "Session不存在或者已经失效!"),
    PASSWORD_EMPTY(30006, "登录密码不能为空!"),
    MOBILE_EMPTY(30007, "手机号不能为空!"),
    MOBILE_ERROR(30008, "手机号格式错误!"),
    MOBILE_NOT_EXIST(30009, "账号不存在!"),
    PASSWORD_ERROR(30010, "密码错误!"),
    USER_NOT_EXIST(30011, "用户不存在！"),

    /**
     * 订单模块
     */
    ORDER_NOT_EXIST(60001, "订单不存在"),

    /**
     * 秒杀模块
     */
    MIAOSHA_END(40001, "商品已经秒杀完毕"),
    MIAOSHA_REPEAT(40002, "不能重复秒杀"),
    MIAOSHA_FAIL(40003, "秒杀失败");


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
