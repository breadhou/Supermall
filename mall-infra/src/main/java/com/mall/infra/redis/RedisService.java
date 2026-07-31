package com.mall.infra.redis;

import cn.hutool.core.convert.Convert;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Redis 操作封装。通过 KeyPrefix 自动拼接前缀和过期时间。
 * 基于 Spring 的 StringRedisTemplate，无需管理连接池。
 */
@Component
@RequiredArgsConstructor
public class RedisService {

    private final StringRedisTemplate stringRedisTemplate;

    // ---- get ----

    public <T> T get(KeyPrefix prefix, String key, Class<T> clazz) {
        String realKey = prefix.getPrefix() + key;
        String str = stringRedisTemplate.opsForValue().get(realKey);
        if (str == null) {
            return null;
        }
        // StringRedisTemplate 保存的是纯字符串。基础类型不会带 JSON 对象结构，
        // 不能统一交给 JSONUtil.toBean() 按 JSONObject 解析。
        if (isScalarType(clazz)) {
            return Convert.convert(clazz, str);
        }
        return JSONUtil.toBean(str, clazz);
    }

    private boolean isScalarType(Class<?> clazz) {
        return clazz == String.class
                || clazz == Boolean.class
                || clazz == Character.class
                || clazz.isPrimitive()
                || Number.class.isAssignableFrom(clazz)
                || clazz.isEnum();
    }

    public String get(String key) {
        return stringRedisTemplate.opsForValue().get(key);
    }

    // ---- set ----

    public <T> void set(KeyPrefix prefix, String key, T value) {
        String realKey = prefix.getPrefix() + key;
        String str = JSONUtil.toJsonStr(value);
        int seconds = prefix.expireSeconds();
        if (seconds > 0) {
            stringRedisTemplate.opsForValue().set(realKey, str, seconds, TimeUnit.SECONDS);
        } else {
            stringRedisTemplate.opsForValue().set(realKey, str);
        }
    }

    public void set(String key, String value) {
        stringRedisTemplate.opsForValue().set(key, value);
    }

    public void set(String key, String value, long timeout, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, value, timeout, unit);
    }

    // ---- exists ----

    public boolean exists(KeyPrefix prefix, String key) {
        String realKey = prefix.getPrefix() + key;
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(realKey));
    }

    // ---- delete ----

    public boolean delete(KeyPrefix prefix, String key) {
        String realKey = prefix.getPrefix() + key;
        return Boolean.TRUE.equals(stringRedisTemplate.delete(realKey));
    }

    public void delete(String key) {
        stringRedisTemplate.delete(key);
    }

    // 按前缀批量删除
    public void deleteByPrefix(KeyPrefix prefix) {
        Set<String> keys = stringRedisTemplate.keys(prefix.getPrefix() + "*");
        if (keys != null && !keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
        }
    }

    // ---- incr / decr ----

    public Long incr(KeyPrefix prefix, String key) {
        String realKey = prefix.getPrefix() + key;
        return stringRedisTemplate.opsForValue().increment(realKey);
    }

    public Long incr(KeyPrefix prefix, String key, long delta) {
        String realKey = prefix.getPrefix() + key;
        return stringRedisTemplate.opsForValue().increment(realKey, delta);
    }

    public Long decr(KeyPrefix prefix, String key) {
        String realKey = prefix.getPrefix() + key;
        return stringRedisTemplate.opsForValue().decrement(realKey);
    }

    // ---- expire ----

    public Boolean expire(KeyPrefix prefix, String key, long timeout, TimeUnit unit) {
        String realKey = prefix.getPrefix() + key;
        return stringRedisTemplate.expire(realKey, timeout, unit);
    }

    // ---- setnx (分布式锁用) ----

    public Boolean setIfAbsent(String key, String value, long timeout, TimeUnit unit) {
        return stringRedisTemplate.opsForValue().setIfAbsent(key, value, timeout, unit);
    }
}
