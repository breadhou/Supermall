package com.mall.module.seckill.mq;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the asynchronous seckill order consumer.
 *
 * <p>The acknowledgement semantics matter most: a message may only be
 * dead-lettered when the reservation was claimed and the database transaction
 * did not commit.  Once the order is durable the message must be acknowledged
 * even if the Redis result update fails, otherwise the order would be replayed
 * and duplicated.</p>
 */
@ExtendWith(MockitoExtension.class)
class SeckillConsumerTest {

    private static final long DELIVERY_TAG = 1L;

    @Mock
    private SeckillItemMapper seckillItemMapper;
    @Mock
    private SeckillOrderMapper seckillOrderMapper;
    @Mock
    private OrderMapper orderMapper;
    @Mock
    private OrderItemMapper orderItemMapper;
    @Mock
    private ProductSkuMapper productSkuMapper;
    @Mock
    private AddressMapper addressMapper;
    @Mock
    private SeckillRedisStateService redisStateService;
    @Mock
    private PlatformTransactionManager transactionManager;
    @Mock
    private SeckillMetrics metrics;
    @Mock
    private Channel channel;

    @InjectMocks
    private SeckillConsumer consumer;

    // ---- message validation and claim -------------------------------------

    @Test
    void consume_shouldDeadLetterWhenMessageIsInvalid() throws Exception {
        SeckillMessage invalid = message();
        invalid.setMessageId("  ");

        consumer.consume(invalid, channel, DELIVERY_TAG);

        verify(channel).basicNack(DELIVERY_TAG, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verifyNoInteractions(redisStateService);
    }

    @Test
    void consume_shouldDeadLetterWhenClaimReturnsNull() throws Exception {
        when(redisStateService.claim(any())).thenReturn(null);

        consumer.consume(message(), channel, DELIVERY_TAG);

        verify(channel).basicNack(DELIVERY_TAG, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verify(redisStateService, never()).finalizeSuccess(any(), anyLong());
    }

    @Test
    void consume_shouldAckWithoutProcessingWhenClaimWasAlreadyHandled() throws Exception {
        // 0 means the API-side timeout/rollback already released the reservation.
        when(redisStateService.claim(any())).thenReturn(0L);

        consumer.consume(message(), channel, DELIVERY_TAG);

        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
        verifyNoInteractions(orderMapper);
        verify(redisStateService, never()).finalizeSuccess(any(), anyLong());
    }

    @Test
    void consume_shouldAckWithoutProcessingWhenClaimWasRolledBack() throws Exception {
        when(redisStateService.claim(any())).thenReturn(-1L);

        consumer.consume(message(), channel, DELIVERY_TAG);

        verify(channel).basicAck(DELIVERY_TAG, false);
        verifyNoInteractions(orderMapper);
    }

    // ---- transaction and finalize -----------------------------------------

    @Test
    void consume_shouldPersistOrderAndAckOnHappyPath() throws Exception {
        when(redisStateService.claim(any())).thenReturn(1L);
        when(seckillItemMapper.decrementStock(anyLong(), any())).thenReturn(1);
        when(redisStateService.finalizeSuccess(any(), anyLong())).thenReturn(1L);

        consumer.consume(message(), channel, DELIVERY_TAG);

        ArgumentCaptor<Order> order = ArgumentCaptor.forClass(Order.class);
        verify(orderMapper).insert(order.capture());
        assertEquals(2L, order.getValue().getUserId());
        assertEquals(200L, order.getValue().getAddressId());
        assertEquals("PENDING", order.getValue().getStatus());
        // quantity 2 x seckillPrice 9.90
        assertEquals(0, new BigDecimal("19.80").compareTo(order.getValue().getTotalAmount()));

        ArgumentCaptor<OrderItem> item = ArgumentCaptor.forClass(OrderItem.class);
        verify(orderItemMapper).insert(item.capture());
        assertEquals(order.getValue().getId(), item.getValue().getOrderId());
        assertEquals(100L, item.getValue().getSkuId());

        ArgumentCaptor<SeckillOrder> seckillOrder = ArgumentCaptor.forClass(SeckillOrder.class);
        verify(seckillOrderMapper).insert(seckillOrder.capture());
        assertEquals(order.getValue().getId(), seckillOrder.getValue().getOrderId());

        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(redisStateService).finalizeSuccess(any(), eq(3600L));
        verify(metrics).recordConsumer(eq(true), anyLong());
    }

    @Test
    void consume_shouldAckEvenWhenFinalizeThrows() throws Exception {
        // The order is already committed; a Redis outage must not replay it.
        when(redisStateService.claim(any())).thenReturn(1L);
        when(seckillItemMapper.decrementStock(anyLong(), any())).thenReturn(1);
        when(redisStateService.finalizeSuccess(any(), anyLong()))
                .thenThrow(new RuntimeException("redis unavailable"));

        consumer.consume(message(), channel, DELIVERY_TAG);

        verify(orderMapper).insert(any(Order.class));
        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
        verify(metrics).recordConsumer(eq(true), anyLong());
    }

    @Test
    void consume_shouldAckWhenFinalizeReportsNoUpdate() throws Exception {
        when(redisStateService.claim(any())).thenReturn(1L);
        when(seckillItemMapper.decrementStock(anyLong(), any())).thenReturn(1);
        when(redisStateService.finalizeSuccess(any(), anyLong())).thenReturn(0L);

        consumer.consume(message(), channel, DELIVERY_TAG);

        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
        verify(metrics).recordConsumer(eq(true), anyLong());
    }

    @Test
    void consume_shouldDeadLetterWhenDatabaseStockIsInsufficient() throws Exception {
        when(redisStateService.claim(any())).thenReturn(1L);
        when(seckillItemMapper.decrementStock(anyLong(), any())).thenReturn(0);

        consumer.consume(message(), channel, DELIVERY_TAG);

        verify(channel).basicNack(DELIVERY_TAG, false, false);
        verify(orderMapper, never()).insert(any(Order.class));
        verify(metrics).recordConsumer(eq(false), anyLong());
    }

    // ---- idempotency and compatibility fallbacks --------------------------

    @Test
    void consume_shouldReuseExistingOrderWhenSeckillOrderAlreadyExists() throws Exception {
        SeckillOrder existing = new SeckillOrder()
                .setId(7L)
                .setUserId(2L)
                .setSeckillItemId(3L)
                .setOrderId(999L);
        when(redisStateService.claim(any())).thenReturn(1L);
        when(seckillOrderMapper.selectOne(any())).thenReturn(existing);
        when(redisStateService.finalizeSuccess(any(), anyLong())).thenReturn(1L);

        consumer.consume(message(), channel, DELIVERY_TAG);

        verify(orderMapper, never()).insert(any(Order.class));
        verify(orderItemMapper, never()).insert(any(OrderItem.class));
        verify(seckillItemMapper, never()).decrementStock(anyLong(), any());
        verify(redisStateService).finalizeSuccess(any(), anyLong());
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    void consume_shouldFallBackToSeckillItemWhenSnapshotFieldsAreMissing() throws Exception {
        // Messages produced before the snapshot fields existed carry no sku or price.
        SeckillMessage legacy = message().setSkuId(null).setSeckillPrice(null);
        SeckillItem seckillItem = new SeckillItem()
                .setId(3L)
                .setSkuId(100L)
                .setSeckillPrice(new BigDecimal("9.90"));
        when(redisStateService.claim(any())).thenReturn(1L);
        when(seckillItemMapper.selectById(3L)).thenReturn(seckillItem);
        when(productSkuMapper.selectById(100L)).thenReturn(new ProductSku().setId(100L));
        when(seckillItemMapper.decrementStock(anyLong(), any())).thenReturn(1);
        when(redisStateService.finalizeSuccess(any(), anyLong())).thenReturn(1L);

        consumer.consume(legacy, channel, DELIVERY_TAG);

        verify(seckillItemMapper).selectById(3L);
        verify(productSkuMapper).selectById(100L);
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    void consume_shouldDeadLetterWhenSeckillItemCannotBeResolved() throws Exception {
        SeckillMessage legacy = message().setSkuId(null).setSeckillPrice(null);
        when(redisStateService.claim(any())).thenReturn(1L);
        when(seckillItemMapper.selectById(3L)).thenReturn(null);

        consumer.consume(legacy, channel, DELIVERY_TAG);

        verify(channel).basicNack(DELIVERY_TAG, false, false);
        verify(orderMapper, never()).insert(any(Order.class));
    }

    @Test
    void consume_shouldDeadLetterWhenUserHasNoShippingAddress() throws Exception {
        SeckillMessage withoutAddress = message().setAddressId(null);
        when(redisStateService.claim(any())).thenReturn(1L);
        when(addressMapper.selectOne(any())).thenReturn(null);

        consumer.consume(withoutAddress, channel, DELIVERY_TAG);

        verify(channel).basicNack(DELIVERY_TAG, false, false);
        verify(orderMapper, never()).insert(any(Order.class));
    }

    @Test
    void consume_shouldResolveDefaultAddressWhenMessageCarriesNoAddress() throws Exception {
        SeckillMessage withoutAddress = message().setAddressId(null);
        Address address = new Address().setId(555L).setUserId(2L);
        when(redisStateService.claim(any())).thenReturn(1L);
        when(addressMapper.selectOne(any())).thenReturn(address);
        when(seckillItemMapper.decrementStock(anyLong(), any())).thenReturn(1);
        when(redisStateService.finalizeSuccess(any(), anyLong())).thenReturn(1L);

        consumer.consume(withoutAddress, channel, DELIVERY_TAG);

        ArgumentCaptor<Order> order = ArgumentCaptor.forClass(Order.class);
        verify(orderMapper).insert(order.capture());
        assertEquals(555L, order.getValue().getAddressId());
    }

    // ---- dead-letter listener ---------------------------------------------

    @Test
    void consumeDeadLetter_shouldDropMalformedMessage() throws Exception {
        SeckillMessage malformed = message();
        malformed.setUserId(null);

        consumer.consumeDeadLetter(malformed, channel, DELIVERY_TAG);

        verify(channel).basicAck(DELIVERY_TAG, false);
        verifyNoInteractions(redisStateService);
    }

    @Test
    void consumeDeadLetter_shouldAckAfterSuccessfulRollback() throws Exception {
        when(redisStateService.rollback(any(), eq(true), anyLong())).thenReturn(1L);

        consumer.consumeDeadLetter(message(), channel, DELIVERY_TAG);

        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    void consumeDeadLetter_shouldRequeueWhenRollbackCannotProceed() throws Exception {
        // -1 means a consumer already claimed the message; leave it for compensation.
        when(redisStateService.rollback(any(), eq(true), anyLong())).thenReturn(-1L);

        consumer.consumeDeadLetter(message(), channel, DELIVERY_TAG);

        verify(channel).basicNack(DELIVERY_TAG, false, true);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    void consumeDeadLetter_shouldRequeueWhenRollbackThrows() throws Exception {
        when(redisStateService.rollback(any(), eq(true), anyLong()))
                .thenThrow(new RuntimeException("redis unavailable"));

        consumer.consumeDeadLetter(message(), channel, DELIVERY_TAG);

        verify(channel).basicNack(DELIVERY_TAG, false, true);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    private SeckillMessage message() {
        return new SeckillMessage()
                .setUserId(2L)
                .setSeckillItemId(3L)
                .setMessageId("message-1")
                .setQuantity(2)
                .setSkuId(100L)
                .setSeckillPrice(new BigDecimal("9.90"))
                .setAddressId(200L);
    }
}
