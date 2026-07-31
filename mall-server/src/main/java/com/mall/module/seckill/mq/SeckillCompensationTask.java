package com.mall.module.seckill.mq;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.module.seckill.entity.po.SeckillOrder;
import com.mall.module.seckill.mapper.SeckillOrderMapper;
import com.mall.module.seckill.monitor.SeckillMetrics;
import com.mall.module.seckill.redis.SeckillRedisStateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Repairs reservations left behind by a publisher timeout, Redis outage or a
 * consumer process restart.  The Lua transitions are idempotent, so multiple
 * API/consumer instances may run this scanner safely.
 */
@Slf4j
@Component
public class SeckillCompensationTask {

    @Autowired
    private SeckillRedisStateService redisStateService;

    @Autowired
    private SeckillOrderMapper seckillOrderMapper;

    @Autowired(required = false)
    private SeckillMetrics metrics;

    @Value("${mall.seckill.pending-timeout-ms:30000}")
    private long pendingTimeoutMs = 30000;

    @Value("${mall.seckill.processing-timeout-ms:120000}")
    private long processingTimeoutMs = 120000;

    @Scheduled(fixedDelayString = "${mall.seckill.compensation.fixed-delay-ms:5000}")
    public void repairPendingMessages() {
        long now = System.currentTimeMillis();
        long cutoff = now - pendingTimeoutMs;
        long pendingCount = 0;

        for (String itemValue : redisStateService.registeredItems()) {
            Long itemId;
            try {
                itemId = Long.valueOf(itemValue);
            } catch (NumberFormatException exception) {
                log.warn("Ignoring malformed seckill item registry entry: {}", itemValue);
                continue;
            }

            pendingCount += redisStateService.pendingCount(itemId);
            Set<String> messageIds = redisStateService.pendingMessageIds(itemId, cutoff);
            for (String messageId : messageIds) {
                repairOne(itemId, messageId, now);
            }
        }
        if (metrics != null) {
            metrics.setPendingMessages(pendingCount);
        }
    }

    private void repairOne(Long itemId, String messageId, long now) {
        SeckillRedisStateService.PendingEntry pending =
                redisStateService.getPending(itemId, messageId);
        if (pending == null) {
            return;
        }

        SeckillMessage message = new SeckillMessage()
                .setUserId(pending.userId())
                .setSeckillItemId(itemId)
                .setMessageId(messageId)
                .setQuantity(pending.quantity());

        SeckillOrder existing = seckillOrderMapper.selectOne(
                new LambdaQueryWrapper<SeckillOrder>()
                        .eq(SeckillOrder::getUserId, pending.userId())
                        .eq(SeckillOrder::getSeckillItemId, itemId)
        );
        if (existing != null) {
            Long result = redisStateService.finalizeSuccess(message, 3600);
            if (result != null && (result == 1L || result == 2L)) {
                record("finalized");
            }
            return;
        }

        boolean processing = "PROCESSING".equals(pending.state());
        boolean processingExpired = pending.processingAt() != null
                && pending.processingAt() <= now - processingTimeoutMs;
        if (processing && !processingExpired) {
            return;
        }

        Long result = redisStateService.rollback(message, true);
        if (result != null && (result == 1L || result == 2L)) {
            record("rolled_back");
        }
    }

    private void record(String outcome) {
        if (metrics != null) {
            metrics.recordCompensation(outcome);
        }
    }
}
