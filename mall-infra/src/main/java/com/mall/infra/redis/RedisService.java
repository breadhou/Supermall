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

    /**
     * Read a value when the caller already owns the complete Redis key.
     *
     * <p>This is used by key families that need a Redis Cluster hash tag.  A
     * normal {@link KeyPrefix} is still preferred for ordinary business keys;
     * seckill keys are built by {@link SeckillKey} so that every key involved
     * in one Lua invocation is placed in the same slot.</p>
     */
    public <T> T getValue(String key, Class<T> clazz) {
        String value = stringRedisTemplate.opsForValue().get(key);
        if (value == null) {
            return null;
        }
        if (isScalarType(clazz)) {
            return Convert.convert(clazz, value);
        }
        return JSONUtil.toBean(value, clazz);
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

    /** Write a JSON/scalar value using a complete Redis key. */
    public <T> void setValue(String key, T value) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value));
    }

    /** Write a JSON/scalar value using a complete Redis key and TTL. */
    public <T> void setValue(String key, T value, long timeout, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), timeout, unit);
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

    /** Counter operation when the caller already owns the complete key. */
    public Long decr(String key) {
        return stringRedisTemplate.opsForValue().decrement(key);
    }

    /** Counter operation when the caller already owns the complete key. */
    public Long incr(String key) {
        return stringRedisTemplate.opsForValue().increment(key);
    }

    /** Counter operation when the caller already owns the complete key. */
    public Long incr(String key, long delta) {
        return stringRedisTemplate.opsForValue().increment(key, delta);
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

    /**
     * SETNX without an expiry.  This is useful for counters whose lifecycle
     * is the same as the business object, such as a coupon stock snapshot.
     */
    public Boolean setIfAbsent(String key, String value) {
        return stringRedisTemplate.opsForValue().setIfAbsent(key, value);
    }
}
