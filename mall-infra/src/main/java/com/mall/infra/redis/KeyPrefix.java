package com.mall.infra.redis;

/**
 * Redis Key 前缀接口，定义过期时间和前缀生成规则。
 */
public interface KeyPrefix {

    public int expireSeconds();

    public String getPrefix();

}
