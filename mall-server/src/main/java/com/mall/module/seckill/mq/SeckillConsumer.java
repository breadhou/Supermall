package com.mall.module.seckill.mq;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.common.utils.SnowflakeIdUtil;
import com.mall.infra.rabbitmq.MQConfig;
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
import com.mall.module.seckill.monitor.SeckillMetrics;
import com.mall.module.seckill.redis.SeckillRedisStateService;
import com.mall.module.user.entity.po.Address;
import com.mall.module.user.mapper.AddressMapper;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.math.BigDecimal;

/**
 * Asynchronous seckill order consumer.  It claims the Redis reservation before
 * entering MySQL, uses manual acknowledgements, and never rejects a committed
 * database transaction merely because the result-cache update is temporarily
 * unavailable.
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
    private SeckillRedisStateService redisStateService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired(required = false)
    private SeckillMetrics metrics;

    @Value("${mall.seckill.result-ttl-seconds:3600}")
    private long resultTtlSeconds = 3600;

    @RabbitListener(
            queues = MQConfig.SECKILL_QUEUE,
            ackMode = "MANUAL",
            concurrency = "${mall.seckill.consumer.concurrency:8}"
    )
    public void consume(
            SeckillMessage message,
            Channel channel,
            @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag
    ) {
        long started = System.nanoTime();
        try {
            validateMessage(message);

            Long claimResult = redisStateService.claim(message);
            if (claimResult == null) {
                throw new IllegalStateException("Unable to claim seckill reservation");
            }
            if (claimResult != 1L) {
                // The API timeout/rollback may have won the race, or another
                // delivery may already be processing this message.
                channel.basicAck(deliveryTag, false);
                recordConsumer(true, started);
                return;
            }

            OrderProcessResult result = new TransactionTemplate(transactionManager)
                    .execute(status -> createOrderInTransaction(message));
            if (result == null) {
                throw new IllegalStateException("Seckill order transaction returned no result");
            }

            // The transaction is committed at this point.  Keep the message
            // acknowledged if Redis is down; the pending scanner will repair
            // the result from the durable seckill_order row.
            try {
                Long finalizeResult = redisStateService.finalizeSuccess(message, resultTtlSeconds);
                if (finalizeResult == null || finalizeResult == 0L) {
                    log.error("Order committed but seckill result was not finalized, messageId={}, orderId={}",
                            message.getMessageId(), result.orderId());
                }
            } catch (RuntimeException exception) {
                log.error("Order committed but Redis result update failed, messageId={}, orderId={}",
                        message.getMessageId(), result.orderId(), exception);
            }

            channel.basicAck(deliveryTag, false);
            recordConsumer(true, started);
        } catch (Exception exception) {
            recordConsumer(false, started);
            log.error("Seckill order message failed, messageId={}",
                    message == null ? null : message.getMessageId(), exception);
            rejectToDeadLetter(channel, deliveryTag);
        }
    }

    @RabbitListener(
            queues = MQConfig.SECKILL_DLQ_QUEUE,
            ackMode = "MANUAL",
            concurrency = "${mall.seckill.compensation.concurrency:1}"
    )
    public void consumeDeadLetter(
            SeckillMessage message,
            Channel channel,
            @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag
    ) {
        try {
            if (!isValidMessage(message)) {
                log.error("Dropping malformed seckill dead-letter message: {}", message);
                channel.basicAck(deliveryTag, false);
                return;
            }

            Long rollbackResult = redisStateService.rollback(message, true, resultTtlSeconds);
            if (rollbackResult == null || rollbackResult == -1L) {
                throw new IllegalStateException("Unable to roll back seckill reservation");
            }
            channel.basicAck(deliveryTag, false);
        } catch (Exception exception) {
            log.error("Seckill dead-letter compensation failed, messageId={}",
                    message == null ? null : message.getMessageId(), exception);
            try {
                channel.basicNack(deliveryTag, false, true);
            } catch (IOException nackException) {
                log.error("Unable to requeue seckill dead-letter message, messageId={}",
                        message == null ? null : message.getMessageId(), nackException);
            }
        }
    }

    private OrderProcessResult createOrderInTransaction(SeckillMessage message) {
        Long userId = message.getUserId();
        Long itemId = message.getSeckillItemId();
        Integer quantity = message.getQuantity();

        SeckillOrder existing = seckillOrderMapper.selectOne(
                new LambdaQueryWrapper<SeckillOrder>()
                        .eq(SeckillOrder::getUserId, userId)
                        .eq(SeckillOrder::getSeckillItemId, itemId)
        );
        if (existing != null) {
            return new OrderProcessResult(existing.getOrderId(), false);
        }

        BigDecimal seckillPrice = message.getSeckillPrice();
        Long skuId = message.getSkuId();
        Long addressId = message.getAddressId();

        // Keep a compatibility fallback for messages produced before the
        // snapshot fields were introduced.  New messages do not execute these
        // reads on the consumer hot path.
        if (seckillPrice == null || skuId == null) {
            SeckillItem seckillItem = seckillItemMapper.selectById(itemId);
            if (seckillItem == null || seckillItem.getSeckillPrice() == null) {
                throw new IllegalStateException("Seckill item does not exist or has no price");
            }
            if (seckillPrice == null) {
                seckillPrice = seckillItem.getSeckillPrice();
            }
            if (skuId == null) {
                skuId = seckillItem.getSkuId();
            }
        }

        if (skuId == null) {
            throw new IllegalStateException("Associated SKU does not exist");
        }
        if (message.getSkuId() == null) {
            ProductSku sku = productSkuMapper.selectById(skuId);
            if (sku == null) {
                throw new IllegalStateException("Associated SKU does not exist");
            }
        }

        if (addressId == null) {
            Address address = addressMapper.selectOne(
                    new LambdaQueryWrapper<Address>()
                            .eq(Address::getUserId, userId)
                            .orderByDesc(Address::getIsDefault)
                            .orderByDesc(Address::getId)
                            .last("LIMIT 1")
            );
            if (address == null) {
                throw new IllegalStateException("User has no shipping address");
            }
            addressId = address.getId();
        }

        int updated = seckillItemMapper.decrementStock(itemId, quantity);
        if (updated != 1) {
            throw new IllegalStateException("Database seckill stock is insufficient");
        }

        Long orderId = SnowflakeIdUtil.nextId();
        BigDecimal totalAmount = seckillPrice.multiply(BigDecimal.valueOf(quantity));

        Order order = new Order()
                .setId(orderId)
                .setOrderNo(String.valueOf(orderId))
                .setUserId(userId)
                .setAddressId(addressId)
                .setTotalAmount(totalAmount)
                .setStatus("PENDING");
        orderMapper.insert(order);

        OrderItem orderItem = new OrderItem()
                .setId(SnowflakeIdUtil.nextId())
                .setOrderId(orderId)
                .setSkuId(skuId)
                .setPrice(seckillPrice)
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

    private void validateMessage(SeckillMessage message) {
        if (!isValidMessage(message)) {
            throw new IllegalArgumentException("Invalid seckill message");
        }
    }

    private boolean isValidMessage(SeckillMessage message) {
        return message != null
                && message.getUserId() != null
                && message.getSeckillItemId() != null
                && message.getMessageId() != null
                && !message.getMessageId().isBlank()
                && message.getQuantity() != null
                && message.getQuantity() > 0;
    }

    private void rejectToDeadLetter(Channel channel, long deliveryTag) {
        try {
            channel.basicNack(deliveryTag, false, false);
        } catch (IOException nackException) {
            log.error("Unable to dead-letter seckill message, deliveryTag={}", deliveryTag, nackException);
        }
    }

    private void recordConsumer(boolean success, long started) {
        if (metrics != null) {
            metrics.recordConsumer(success, System.nanoTime() - started);
        }
    }

    private record OrderProcessResult(Long orderId, boolean created) {
    }
}
