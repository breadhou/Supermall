package com.mall.module.seckill.redis;

import com.mall.infra.redis.SeckillKey;
import com.mall.module.seckill.mq.SeckillMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Atomic state transitions for the seckill reservation lifecycle.
 *
 * <p>The service deliberately exposes transitions instead of individual Redis
 * operations.  This keeps reserve, claim, finalize and rollback consistent on
 * every API/consumer instance.</p>
 */
@Component
@RequiredArgsConstructor
public class SeckillRedisStateService {

    private static final DefaultRedisScript<Long> RESERVE_SCRIPT = script("Lua/seckill_stock.lua");
    private static final DefaultRedisScript<Long> CLAIM_SCRIPT = script("Lua/seckill_claim.lua");
    private static final DefaultRedisScript<Long> FINALIZE_SCRIPT = script("Lua/seckill_finalize.lua");
    private static final DefaultRedisScript<Long> ROLLBACK_SCRIPT = script("Lua/seckill_rollback.lua");

    private final StringRedisTemplate redisTemplate;

    /**
     * Reserve stock and create a pending message in one Lua transaction.
     * Return codes are documented in seckill_stock.lua.
     */
    public Long reserve(Long itemId,
                        Long userId,
                        String path,
                        Integer quantity,
                        Integer limitPerUser,
                        String messageId,
                        long resultTtlSeconds) {
        List<String> keys = List.of(
                SeckillKey.pathKey(itemId, userId),
                SeckillKey.resultKey(itemId, userId),
                SeckillKey.userLimitKey(itemId, userId),
                SeckillKey.stockKey(itemId),
                SeckillKey.pendingKey(itemId, messageId),
                SeckillKey.pendingIndexKey(itemId)
        );
        return redisTemplate.execute(
                RESERVE_SCRIPT,
                keys,
                path,
                String.valueOf(userId),
                String.valueOf(quantity),
                String.valueOf(limitPerUser),
                messageId,
                String.valueOf(System.currentTimeMillis()),
                String.valueOf(resultTtlSeconds)
        );
    }

    /** Claim a reservation before the consumer starts its database transaction. */
    public Long claim(SeckillMessage message) {
        List<String> keys = List.of(
                SeckillKey.pendingKey(message.getSeckillItemId(), message.getMessageId()),
                SeckillKey.resultKey(message.getSeckillItemId(), message.getUserId())
        );
        return redisTemplate.execute(
                CLAIM_SCRIPT,
                keys,
                String.valueOf(System.currentTimeMillis())
        );
    }

    /**
     * Mark a committed order successful and remove the pending reservation.
     */
    public Long finalizeSuccess(SeckillMessage message, long resultTtlSeconds) {
        List<String> keys = List.of(
                SeckillKey.resultKey(message.getSeckillItemId(), message.getUserId()),
                SeckillKey.pendingKey(message.getSeckillItemId(), message.getMessageId()),
                SeckillKey.pendingIndexKey(message.getSeckillItemId())
        );
        return redisTemplate.execute(
                FINALIZE_SCRIPT,
                keys,
                message.getMessageId(),
                String.valueOf(resultTtlSeconds)
        );
    }

    /**
     * Return reserved stock and mark the request failed.  A return value of 3
     * means a consumer already claimed the message, so the caller must leave
     * the state for the consumer/compensation task to finish.
     */
    public Long rollback(SeckillMessage message, boolean allowProcessing, long resultTtlSeconds) {
        List<String> keys = List.of(
                SeckillKey.stockKey(message.getSeckillItemId()),
                SeckillKey.resultKey(message.getSeckillItemId(), message.getUserId()),
                SeckillKey.userLimitKey(message.getSeckillItemId(), message.getUserId()),
                SeckillKey.pendingKey(message.getSeckillItemId(), message.getMessageId()),
                SeckillKey.pendingIndexKey(message.getSeckillItemId())
        );
        return redisTemplate.execute(
                ROLLBACK_SCRIPT,
                keys,
                message.getMessageId(),
                String.valueOf(message.getUserId()),
                String.valueOf(message.getQuantity()),
                allowProcessing ? "1" : "0",
                String.valueOf(resultTtlSeconds)
        );
    }

    public void registerItem(Long itemId) {
        redisTemplate.opsForSet().add(SeckillKey.itemIndexKey(), String.valueOf(itemId));
    }

    public Set<String> registeredItems() {
        Set<String> itemIds = redisTemplate.opsForSet().members(SeckillKey.itemIndexKey());
        return itemIds == null ? Collections.emptySet() : itemIds;
    }

    public Set<String> pendingMessageIds(Long itemId, long cutoffEpochMillis) {
        Set<String> messageIds = redisTemplate.opsForZSet().rangeByScore(
                SeckillKey.pendingIndexKey(itemId),
                0,
                cutoffEpochMillis
        );
        return messageIds == null ? Collections.emptySet() : messageIds;
    }

    public long pendingCount(Long itemId) {
        Long count = redisTemplate.opsForZSet().zCard(SeckillKey.pendingIndexKey(itemId));
        return count == null ? 0 : count;
    }

    public PendingEntry getPending(Long itemId, String messageId) {
        String value = redisTemplate.opsForValue().get(SeckillKey.pendingKey(itemId, messageId));
        if (value == null) {
            return null;
        }

        String[] parts = value.split("\\|", -1);
        if (parts.length < 5) {
            return null;
        }
        try {
            return new PendingEntry(
                    parts[0],
                    Long.valueOf(parts[1]),
                    Integer.valueOf(parts[2]),
                    Long.valueOf(parts[3]),
                    parts[4],
                    parts.length > 5 ? Long.valueOf(parts[5]) : null
            );
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static DefaultRedisScript<Long> script(String location) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(location));
        script.setResultType(Long.class);
        return script;
    }

    public record PendingEntry(
            String state,
            Long userId,
            Integer quantity,
            Long createdAt,
            String messageId,
            Long processingAt
    ) {
    }
}
