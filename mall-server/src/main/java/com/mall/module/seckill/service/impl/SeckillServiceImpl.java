package com.mall.module.seckill.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.common.enums.ResultStatus;
import com.mall.common.exception.BusinessException;
import com.mall.infra.redis.RedisService;
import com.mall.infra.redis.SeckillKey;
import com.mall.module.seckill.entity.po.SeckillActivity;
import com.mall.module.seckill.entity.po.SeckillItem;
import com.mall.module.seckill.entity.po.SeckillOrder;
import com.mall.module.seckill.entity.vo.SeckillCountdownVO;
import com.mall.module.seckill.entity.vo.SeckillItemSnapshot;
import com.mall.module.seckill.entity.vo.SeckillRequestSnapshot;
import com.mall.module.seckill.entity.vo.SeckillResultVO;
import com.mall.module.seckill.mapper.SeckillActivityMapper;
import com.mall.module.seckill.mapper.SeckillItemMapper;
import com.mall.module.seckill.mapper.SeckillOrderMapper;
import com.mall.module.seckill.monitor.SeckillMetrics;
import com.mall.module.seckill.mq.SeckillMessage;
import com.mall.module.seckill.mq.SeckillMessagePublisher;
import com.mall.module.seckill.redis.SeckillRedisStateService;
import com.mall.module.seckill.service.SeckillService;
import com.mall.module.user.entity.po.Address;
import com.mall.module.user.mapper.AddressMapper;
import com.mall.security.utils.UserContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.UUID;

@Service
public class SeckillServiceImpl implements SeckillService {

    @Autowired
    private SeckillActivityMapper activityMapper;

    @Autowired
    private SeckillItemMapper itemMapper;

    @Autowired
    private SeckillOrderMapper orderMapper;

    @Autowired
    private AddressMapper addressMapper;

    @Autowired
    private RedisService redisService;

    @Autowired
    private SeckillRedisStateService redisStateService;

    @Autowired
    private SeckillMessagePublisher messagePublisher;

    @Autowired(required = false)
    private SeckillMetrics metrics;

    @Value("${mall.seckill.path-ttl-seconds:60}")
    private long pathTtlSeconds = 60;

    @Value("${mall.seckill.result-ttl-seconds:3600}")
    private long resultTtlSeconds = 3600;

    @Value("${mall.seckill.publisher-confirm-timeout-ms:1000}")
    private long publisherConfirmTimeoutMs = 1000;

    @Override
    public void preheatStock(Long itemId) {
        if (itemId == null) {
            throw new BusinessException(ResultStatus.PARAM_ERROR);
        }

        SeckillItem item = itemMapper.selectById(itemId);
        if (item == null) {
            throw new BusinessException(ResultStatus.DATA_NOT_FOUND);
        }
        if (item.getStock() == null || item.getStock() < 0
                || item.getSkuId() == null || item.getSeckillPrice() == null
                || item.getLimitPerUser() == null || item.getLimitPerUser() <= 0) {
            throw new BusinessException(ResultStatus.SECKILL_FAIL);
        }

        SeckillItemSnapshot snapshot = new SeckillItemSnapshot()
                .setItemId(itemId)
                .setSkuId(item.getSkuId())
                .setSeckillPrice(item.getSeckillPrice())
                .setLimitPerUser(item.getLimitPerUser());
        redisService.setValue(SeckillKey.stockKey(itemId), item.getStock());
        redisService.setValue(SeckillKey.itemSnapshotKey(itemId), snapshot);
        redisStateService.registerItem(itemId);
    }

    @Override
    public String getPath(Long itemId) {
        Long userId = UserContext.getUserId();
        if (itemId == null || userId == null) {
            throw new BusinessException(ResultStatus.PARAM_ERROR);
        }

        SeckillItem item = itemMapper.selectById(itemId);
        if (item == null) {
            throw new BusinessException(ResultStatus.DATA_NOT_FOUND);
        }

        LocalDateTime now = LocalDateTime.now();
        SeckillActivity activity = activityMapper.selectById(item.getActivityId());
        if (activity == null) {
            throw new BusinessException(ResultStatus.DATA_NOT_FOUND);
        }
        if (now.isBefore(activity.getStartTime()) || now.isAfter(activity.getEndTime())) {
            throw new BusinessException(ResultStatus.SECKILL_END);
        }

        Address address = addressMapper.selectOne(
                new LambdaQueryWrapper<Address>()
                        .eq(Address::getUserId, userId)
                        .orderByDesc(Address::getIsDefault)
                        .orderByDesc(Address::getId)
                        .last("LIMIT 1")
        );
        if (address == null) {
            throw new BusinessException(ResultStatus.SECKILL_FAIL);
        }

        String path = UUID.randomUUID().toString();
        SeckillRequestSnapshot requestSnapshot = new SeckillRequestSnapshot()
                .setPath(path)
                .setSkuId(item.getSkuId())
                .setSeckillPrice(item.getSeckillPrice())
                .setLimitPerUser(item.getLimitPerUser())
                .setAddressId(address.getId());

        redisService.set(
                SeckillKey.pathKey(itemId, userId),
                path,
                pathTtlSeconds,
                TimeUnit.SECONDS
        );
        redisService.setValue(
                SeckillKey.requestSnapshotKey(itemId, userId),
                requestSnapshot,
                pathTtlSeconds,
                TimeUnit.SECONDS
        );
        return path;
    }

    @Override
    public SeckillCountdownVO getCountdown(Long itemId) {
        if (itemId == null) {
            throw new BusinessException(ResultStatus.PARAM_ERROR);
        }

        SeckillItem item = itemMapper.selectById(itemId);
        if (item == null) {
            throw new BusinessException(ResultStatus.DATA_NOT_FOUND);
        }

        SeckillActivity activity = activityMapper.selectById(item.getActivityId());
        if (activity == null) {
            throw new BusinessException(ResultStatus.DATA_NOT_FOUND);
        }

        SeckillCountdownVO vo = new SeckillCountdownVO();
        vo.setActivityStatus(activity.getStatus());
        vo.setEndTime(activity.getEndTime());
        vo.setStartTime(activity.getStartTime());
        vo.setLimitPerUser(item.getLimitPerUser());
        vo.setRemainingStock(redisService.getValue(SeckillKey.stockKey(itemId), Integer.class));
        vo.setSeckillPrice(item.getSeckillPrice());
        return vo;
    }

    @Override
    public SeckillResultVO executeSeckill(Long itemId, String path) {
        Long userId = UserContext.getUserId();
        if (itemId == null || userId == null || path == null || path.isBlank()) {
            throw new BusinessException(ResultStatus.PARAM_ERROR);
        }

        // This is the only read before Lua.  It is a Redis snapshot, not a
        // database query; the execute endpoint deliberately stays DB-free.
        SeckillRequestSnapshot snapshot = redisService.getValue(
                SeckillKey.requestSnapshotKey(itemId, userId),
                SeckillRequestSnapshot.class
        );
        if (snapshot == null || snapshot.getSkuId() == null
                || snapshot.getSeckillPrice() == null
                || snapshot.getLimitPerUser() == null || snapshot.getAddressId() == null) {
            recordEntry("snapshot_missing");
            throw new BusinessException(ResultStatus.SECKILL_FAIL);
        }

        SeckillMessage message = new SeckillMessage();
        message.setUserId(userId);
        message.setSeckillItemId(itemId);
        message.setMessageId(UUID.randomUUID().toString());
        message.setQuantity(1);
        message.setSkuId(snapshot.getSkuId());
        message.setSeckillPrice(snapshot.getSeckillPrice());
        message.setAddressId(snapshot.getAddressId());

        long luaStarted = System.nanoTime();
        Long reserveResult;
        try {
            reserveResult = redisStateService.reserve(
                    itemId,
                    userId,
                    path,
                    message.getQuantity(),
                    snapshot.getLimitPerUser(),
                    message.getMessageId(),
                    resultTtlSeconds
            );
        } catch (RuntimeException exception) {
            recordLua(luaStarted);
            recordEntry("lua_error");
            throw new BusinessException(ResultStatus.SECKILL_FAIL);
        }
        recordLua(luaStarted);

        if (reserveResult == null || reserveResult == -1L || reserveResult == -2L || reserveResult == -3L) {
            recordEntry(reserveResult != null && reserveResult == -3L ? "path_invalid" : "lua_failed");
            throw new BusinessException(ResultStatus.SECKILL_FAIL);
        }
        if (reserveResult == 0L) {
            recordEntry("stock_empty");
            throw new BusinessException(ResultStatus.SECKILL_END);
        }
        if (reserveResult == -4L) {
            recordEntry("repeat");
            throw new BusinessException(ResultStatus.SECKILL_REPEAT);
        }
        if (reserveResult != 1L) {
            recordEntry("lua_failed");
            throw new BusinessException(ResultStatus.SECKILL_FAIL);
        }

        try {
            CompletableFuture<SeckillMessagePublisher.PublishResult> future =
                    messagePublisher.publishAsync(message);
            SeckillMessagePublisher.PublishResult publishResult = future.get(
                    publisherConfirmTimeoutMs,
                    TimeUnit.MILLISECONDS
            );
            if (publishResult == null || !publishResult.confirmed()) {
                rollbackAfterPublishFailure(message);
                recordEntry("publish_failed");
                throw new BusinessException(ResultStatus.SECKILL_FAIL);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            rollbackAfterPublishFailure(message);
            recordEntry("publish_timeout");
            throw new BusinessException(ResultStatus.SECKILL_FAIL);
        } catch (ExecutionException | TimeoutException exception) {
            rollbackAfterPublishFailure(message);
            recordEntry(exception instanceof TimeoutException ? "publish_timeout" : "publish_failed");
            throw new BusinessException(ResultStatus.SECKILL_FAIL);
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            rollbackAfterPublishFailure(message);
            recordEntry("publish_failed");
            throw new BusinessException(ResultStatus.SECKILL_FAIL);
        }

        recordEntry("waiting");
        SeckillResultVO vo = new SeckillResultVO();
        vo.setStatus("WAITING");
        return vo;
    }

    @Override
    public SeckillResultVO pollResult(Long itemId) {
        Long userId = UserContext.getUserId();
        if (itemId == null || userId == null) {
            throw new BusinessException(ResultStatus.PARAM_ERROR);
        }

        Integer resultState = redisService.getValue(
                SeckillKey.resultKey(itemId, userId),
                Integer.class
        );

        SeckillResultVO vo = new SeckillResultVO();
        if (resultState == null) {
            vo.setStatus("NOT_FOUND");
            vo.setReason("未参与秒杀");
            return vo;
        }
        if (resultState == 0) {
            vo.setStatus("WAITING");
            return vo;
        }
        if (resultState == 1) {
            vo.setStatus("SUCCESS");
            SeckillOrder seckillOrder = orderMapper.selectOne(
                    new LambdaQueryWrapper<SeckillOrder>()
                            .eq(SeckillOrder::getUserId, userId)
                            .eq(SeckillOrder::getSeckillItemId, itemId)
            );
            if (seckillOrder != null) {
                vo.setOrderId(seckillOrder.getOrderId());
            }
            return vo;
        }

        vo.setStatus("FAILED");
        vo.setReason("库存不足或订单处理失败");
        return vo;
    }

    private void rollbackAfterPublishFailure(SeckillMessage message) {
        try {
            Long rollbackResult = redisStateService.rollback(message, false);
            if (rollbackResult != null && rollbackResult == 3L) {
                // A consumer won the race.  It owns the reservation now; the
                // consumer or compensation task will finalize it safely.
            }
        } catch (RuntimeException exception) {
            // Leave the pending state for the scheduled compensation task.
        }
    }

    private void recordLua(long started) {
        if (metrics != null) {
            metrics.recordLua(System.nanoTime() - started);
        }
    }

    private void recordEntry(String outcome) {
        if (metrics != null) {
            metrics.recordEntry(outcome);
        }
    }
}
