package com.mall.infra.redis;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Redis 分布式锁。
 *
 * 加锁：SET key value NX EX timeout，原子操作，不存在才写入。
 * 解锁：Lua 脚本校验 value 后删除，防止误删别人的锁。
 *
 * 用法：
 *   String lockValue = redisLock.lock("order:123", 10, TimeUnit.SECONDS);
 *   try { ... } finally { redisLock.unlock("order:123", lockValue); }
 */
@Component
@RequiredArgsConstructor
public class RedisLock {

    private final StringRedisTemplate stringRedisTemplate;

    private static final String UNLOCK_LUA =
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
            "    return redis.call('del', KEYS[1]) " +
            "else " +
            "    return 0 " +
            "end";

    /**
     * 加锁，返回锁标识（解锁时需要传入相同标识）。
     * @param key      锁的 key
     * @param timeout  持有锁的最长时间
     * @param unit     时间单位
     * @return 锁标识，加锁失败返回 null
     */
    public String lock(String key, long timeout, TimeUnit unit) {
        String value = UUID.randomUUID().toString();
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, value, timeout, unit);
        return Boolean.TRUE.equals(success) ? value : null;
    }

    /**
     * 解锁。仅当 value 匹配时才删除 key。
     */
    public boolean unlock(String key, String value) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(UNLOCK_LUA, Long.class);
        Long result = stringRedisTemplate.execute(script, Collections.singletonList(key), value);
        return Long.valueOf(1).equals(result);
    }
}
