package com.mall.module.seckill.mq;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.common.utils.SnowflakeIdUtil;
import com.mall.infra.rabbitmq.MQConfig;
import com.mall.infra.redis.RedisService;
import com.mall.infra.redis.SeckillKey;
import com.mall.module.order.entity.po.Order;
import com.mall.module.order.entity.po.OrderItem;
import com.mall.module.order.mapper.OrderItemMapper;
import com.mall.module.order.mapper.OrderMapper;
import com.mall.module.product.entity.po.ProductSku;
import com.mall.module.product.mapper.ProductSkuMapper;
import com.mall.module.seckill.entity.po.SeckillItem;
import com.mall.module.seckill.entity.po.SeckillOrder;
import com.mall.module.seckill.mapper.SeckillItemMapper;
import com.mall.module.seckill.mapper.SeckillOrderMapper;
import com.mall.module.user.entity.po.Address;
import com.mall.module.user.mapper.AddressMapper;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.math.BigDecimal;

/**
 * 秒杀订单消息消费者。
 *
 * 主队列负责事务落库，数据库事务提交后才确认消息；处理失败时拒绝消息，
 * 由 MQConfig 配置的死信交换机转入死信队列。死信监听器负责回滚 Redis
 * 预扣库存并将秒杀结果标记为失败。
 */
@Slf4j
@Component
public class SeckillConsumer {

    @Autowired
    private SeckillItemMapper seckillItemMapper;

    @Autowired
    private SeckillOrderMapper seckillOrderMapper;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderItemMapper orderItemMapper;

    @Autowired
    private ProductSkuMapper productSkuMapper;

    @Autowired
    private AddressMapper addressMapper;

    @Autowired
    private RedisService redisService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /**
     * 消费秒杀订单消息。
     */
    @RabbitListener(
            queues = MQConfig.SECKILL_QUEUE,
            ackMode = "MANUAL"
    )
    public void consume(
            SeckillMessage message,
            Channel channel,
            @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag
    ) {
        try {
            validateMessage(message);

            OrderProcessResult result = new TransactionTemplate(transactionManager)
                    .execute(status -> createOrderInTransaction(message));
            if (result == null) {
                throw new IllegalStateException("秒杀订单事务未返回结果");
            }

            markSuccess(message, result);
            channel.basicAck(deliveryTag, false);
        } catch (Exception exception) {
            log.error("秒杀订单消息消费失败，messageId={}",
                    message == null ? null : message.getMessageId(), exception);
            rejectToDeadLetter(channel, deliveryTag);
        }
    }

    /**
     * 死信处理：回滚 Redis 预扣库存，并让前端轮询到 FAILED。
     */
    @RabbitListener(
            queues = MQConfig.SECKILL_DLQ_QUEUE,
            ackMode = "MANUAL"
    )
    public void consumeDeadLetter(
            SeckillMessage message,
            Channel channel,
            @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag
    ) {
        try {
            if (!isValidMessage(message)) {
                log.error("丢弃格式非法的秒杀死信消息，message={}", message);
                channel.basicAck(deliveryTag, false);
                return;
            }

            rollbackRedisState(message);
            channel.basicAck(deliveryTag, false);
        } catch (Exception exception) {
            log.error("秒杀死信处理失败，messageId={}", message.getMessageId(), exception);
            try {
                // Redis 暂时不可用时重新入队，等待下一次补偿。
                channel.basicNack(deliveryTag, false, true);
            } catch (IOException nackException) {
                log.error("秒杀死信重新入队失败，messageId={}", message.getMessageId(), nackException);
            }
        }
    }

    private OrderProcessResult createOrderInTransaction(SeckillMessage message) {
        Long userId = message.getUserId();
        Long itemId = message.getSeckillItemId();
        Integer quantity = message.getQuantity();

        // 消息重复投递时直接返回已有订单，避免再次扣库存和创建订单。
        SeckillOrder existing = seckillOrderMapper.selectOne(
                new LambdaQueryWrapper<SeckillOrder>()
                        .eq(SeckillOrder::getUserId, userId)
                        .eq(SeckillOrder::getSeckillItemId, itemId)
        );
        if (existing != null) {
            return new OrderProcessResult(existing.getOrderId(), false);
        }

        SeckillItem seckillItem = seckillItemMapper.selectById(itemId);
        if (seckillItem == null || seckillItem.getSeckillPrice() == null) {
            throw new IllegalStateException("秒杀商品不存在或价格为空");
        }

        ProductSku sku = productSkuMapper.selectById(seckillItem.getSkuId());
        if (sku == null) {
            throw new IllegalStateException("关联 SKU 不存在");
        }

        // 秒杀订单没有单独携带地址，使用用户当前默认地址；没有默认地址时取最新地址。
        Address address = addressMapper.selectOne(
                new LambdaQueryWrapper<Address>()
                        .eq(Address::getUserId, userId)
                        .orderByDesc(Address::getIsDefault)
                        .orderByDesc(Address::getId)
                        .last("LIMIT 1")
        );
        if (address == null) {
            throw new IllegalStateException("用户没有收货地址");
        }

        int updated = seckillItemMapper.decrementStock(itemId, quantity);
        if (updated != 1) {
            throw new IllegalStateException("数据库库存不足");
        }

        Long orderId = SnowflakeIdUtil.nextId();
        BigDecimal totalAmount = seckillItem.getSeckillPrice()
                .multiply(BigDecimal.valueOf(quantity));

        Order order = new Order()
                .setId(orderId)
                .setOrderNo(String.valueOf(orderId))
                .setUserId(userId)
                .setAddressId(address.getId())
                .setTotalAmount(totalAmount)
                .setStatus("PENDING");
        orderMapper.insert(order);

        OrderItem orderItem = new OrderItem()
                .setId(SnowflakeIdUtil.nextId())
                .setOrderId(orderId)
                .setSkuId(sku.getId())
                .setPrice(seckillItem.getSeckillPrice())
                .setQuantity(quantity);
        orderItemMapper.insert(orderItem);

        SeckillOrder seckillOrder = new SeckillOrder()
                .setId(SnowflakeIdUtil.nextId())
                .setUserId(userId)
                .setSeckillItemId(itemId)
                .setOrderId(orderId);
        seckillOrderMapper.insert(seckillOrder);

        return new OrderProcessResult(orderId, true);
    }

    private void markSuccess(SeckillMessage message, OrderProcessResult result) {
        String key = resultKey(message);
        try {
            if (result.created()) {
                redisService.incr(SeckillKey.userLimit, key);
            }
            redisService.set(SeckillKey.result, key, 1);
        } catch (RuntimeException exception) {
            // 数据库事务已经提交，不能再拒绝消息触发库存回滚；交给后续补偿任务处理。
            log.error("订单已落库但 Redis 结果更新失败，messageId={}, orderId={}",
                    message.getMessageId(), result.orderId(), exception);
        }
    }

    private void rollbackRedisState(SeckillMessage message) {
        redisService.incr(SeckillKey.stock,
                message.getSeckillItemId().toString(),
                message.getQuantity());
        redisService.set(SeckillKey.result, resultKey(message), -1);
    }

    private void validateMessage(SeckillMessage message) {
        if (!isValidMessage(message)) {
            throw new IllegalArgumentException("秒杀消息参数非法");
        }
    }

    private boolean isValidMessage(SeckillMessage message) {
        return message != null
                && message.getUserId() != null
                && message.getSeckillItemId() != null
                && message.getQuantity() != null
                && message.getQuantity() > 0;
    }

    private String resultKey(SeckillMessage message) {
        return message.getSeckillItemId() + ":" + message.getUserId();
    }

    private void rejectToDeadLetter(Channel channel, long deliveryTag) {
        try {
            channel.basicNack(deliveryTag, false, false);
        } catch (IOException nackException) {
            log.error("秒杀消息拒绝并转入死信队列失败，deliveryTag={}", deliveryTag, nackException);
        }
    }

    private record OrderProcessResult(Long orderId, boolean created) {
    }
}
