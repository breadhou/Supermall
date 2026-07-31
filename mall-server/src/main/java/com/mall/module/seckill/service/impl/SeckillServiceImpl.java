package com.mall.module.seckill.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.common.enums.ResultStatus;
import com.mall.common.exception.BusinessException;
import com.mall.infra.rabbitmq.MQConfig;
import com.mall.infra.redis.RedisLock;
import com.mall.infra.redis.RedisService;
import com.mall.infra.redis.SeckillKey;
import com.mall.module.seckill.entity.po.SeckillActivity;
import com.mall.module.seckill.entity.po.SeckillItem;
import com.mall.module.seckill.entity.po.SeckillOrder;
import com.mall.module.seckill.entity.vo.SeckillCountdownVO;
import com.mall.module.seckill.entity.vo.SeckillResultVO;
import com.mall.module.seckill.mapper.SeckillActivityMapper;
import com.mall.module.seckill.mapper.SeckillItemMapper;
import com.mall.module.seckill.mapper.SeckillOrderMapper;
import com.mall.module.seckill.mq.SeckillMessage;
import com.mall.module.seckill.service.SeckillService;
import com.mall.security.utils.UserContext;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class SeckillServiceImpl implements SeckillService {

    @Autowired
    SeckillActivityMapper activityMapper;

    @Autowired
    SeckillItemMapper itemMapper;

    @Autowired
    SeckillOrderMapper orderMapper;

    @Autowired
    RedisService redisService;

    @Autowired
    private RedisLock redisLock;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    private final DefaultRedisScript<Long> redisScript = createRedisScript();

    private static DefaultRedisScript<Long> createRedisScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("Lua/seckill_stock.lua"));
        script.setResultType(Long.class);
        return script;
    }

    @Override
    public void preheatStock(Long itemId) {

        SeckillItem item = itemMapper.selectById(itemId);
        if (item == null) {
            throw new BusinessException(ResultStatus.DATA_NOT_FOUND);
        }
        redisService.set(
                SeckillKey.stock,
                itemId.toString(),
                item.getStock()
        );

    }

    @Override
    public String getPath(Long itemId) {

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

        String path = UUID.randomUUID().toString();
        redisService.set(
                SeckillKey.path,
                itemId + ":" + UserContext.getUserId(),
                path
        );

        return path;

    }

    @Override
    public SeckillCountdownVO getCountdown(Long itemId) {

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
        vo.setRemainingStock(redisService.get(
                SeckillKey.stock,
                itemId.toString(),
                Integer.class
        ));
        vo.setSeckillPrice(item.getSeckillPrice());

        return vo;

    }

    @Override
    public SeckillResultVO executeSeckill(Long itemId, String path) {

        Long userId = UserContext.getUserId();
        if (itemId == null || userId == null) {
            throw new BusinessException(ResultStatus.PARAM_ERROR);
        }

        SeckillItem item = itemMapper.selectById(itemId);
        if (item == null) {
            throw new BusinessException(ResultStatus.DATA_NOT_FOUND);
        }

        String key = itemId + ":" + userId;
        String cachedPath = redisService.get(
                SeckillKey.path,
                key,
                String.class
        );
        if (path == null || !path.equals(cachedPath)) {
            throw new BusinessException(ResultStatus.SECKILL_FAIL);
        }

        String stockKey = SeckillKey.stock.getPrefix() + itemId;
        String lockKey = SeckillKey.userLock.getPrefix() + key;
        String lockValue = redisLock.lock(lockKey, 10, TimeUnit.SECONDS);
        if (lockValue == null) {
            throw new BusinessException(ResultStatus.SECKILL_FAIL);
        }

        try {
            // 0 表示已有请求排队中，1 表示已经成功，均不能再次下单。
            Integer resultState = redisService.get(SeckillKey.result, key, Integer.class);
            if (resultState != null && resultState >= 0) {
                throw new BusinessException(ResultStatus.SECKILL_REPEAT);
            }

            Integer bought = redisService.get(SeckillKey.userLimit, key, Integer.class);
            if (bought != null && bought >= item.getLimitPerUser()) {
                throw new BusinessException(ResultStatus.SECKILL_REPEAT);
            }

            // KEYS[1] = mall:seckill:stock:{itemId}，ARGV[1] = 本次扣减数量。
            Long stockResult = redisTemplate.execute(
                    redisScript,
                    Collections.singletonList(stockKey),
                    "1"
            );
            if (stockResult == null || stockResult < 0L) {
                throw new BusinessException(ResultStatus.SECKILL_FAIL);
            }
            if (stockResult == 0L) {
                throw new BusinessException(ResultStatus.SECKILL_END);
            }

            SeckillMessage message = new SeckillMessage();
            message.setUserId(userId);
            message.setSeckillItemId(itemId);
            message.setMessageId(UUID.randomUUID().toString());
            message.setQuantity(1);

            try {
                // 先标记排队，消费者成功落库后再改为 1。
                redisService.set(SeckillKey.result, key, 0);
                rabbitTemplate.convertAndSend(
                        MQConfig.SECKILL_EXCHANGE,
                        MQConfig.SECKILL_ROUTING_KEY,
                        message
                );
            } catch (RuntimeException exception) {
                // 发送消息发生同步异常时回滚 Redis 预扣库存，并标记本次失败。
                redisTemplate.opsForValue().increment(stockKey);
                redisService.set(SeckillKey.result, key, -1);
                throw new BusinessException(ResultStatus.SECKILL_FAIL);
            }
        } finally {
            redisLock.unlock(lockKey, lockValue);
        }

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

        String key = itemId + ":" + userId;
        Integer resultState = redisService.get(SeckillKey.result, key, Integer.class);

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
}
