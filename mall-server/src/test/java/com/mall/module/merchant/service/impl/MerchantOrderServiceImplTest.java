package com.mall.module.merchant.service.impl;

import com.mall.common.enums.ResultStatus;
import com.mall.common.exception.BusinessException;
import com.mall.module.logistics.entity.dto.ShipOrderDTO;
import com.mall.module.logistics.service.LogisticsService;
import com.mall.module.merchant.mapper.MerchantOrderMapper;
import com.mall.module.order.entity.po.Order;
import com.mall.module.order.mapper.OrderMapper;
import com.mall.security.utils.MerchantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 商家订单的归属隔离。核心不变量：不含本店商品的订单，一律不能发货。
 */
@ExtendWith(MockitoExtension.class)
class MerchantOrderServiceImplTest {

    private static final Long MERCHANT_A = 5001L;
    private static final Long ORDER_ID = 9001L;
    private static final String ORDER_NO = "SN9001";

    @Mock
    private MerchantOrderMapper merchantOrderMapper;
    @Mock
    private OrderMapper orderMapper;
    @Mock
    private LogisticsService logisticsService;

    private MerchantOrderServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new MerchantOrderServiceImpl(merchantOrderMapper, orderMapper, logisticsService);
        MerchantContext.set(1001L, MERCHANT_A);
    }

    @AfterEach
    void tearDown() {
        MerchantContext.clear();
    }

    private void givenOrderExists() {
        when(orderMapper.selectOne(any())).thenReturn(new Order().setId(ORDER_ID).setOrderNo(ORDER_NO));
    }

    @Test
    void ship_shouldRejectOrderWithoutItemsFromThisMerchant() {
        givenOrderExists();
        when(merchantOrderMapper.countStoreOrderItems(ORDER_ID, MERCHANT_A)).thenReturn(0L);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.ship(ORDER_NO, new ShipOrderDTO()));

        assertEquals(ResultStatus.MERCHANT_ORDER_FORBIDDEN, exception.getStatus());
        verify(logisticsService, never()).shipOrderForMerchant(anyLong(), any());
    }

    @Test
    void ship_shouldDelegateToLogisticsWhenOrderContainsStoreItems() {
        givenOrderExists();
        when(merchantOrderMapper.countStoreOrderItems(ORDER_ID, MERCHANT_A)).thenReturn(1L);

        service.ship(ORDER_NO, new ShipOrderDTO());

        verify(logisticsService).shipOrderForMerchant(eq(ORDER_ID), any(ShipOrderDTO.class));
    }

    @Test
    void deliver_shouldRejectOrderWithoutItemsFromThisMerchant() {
        givenOrderExists();
        when(merchantOrderMapper.countStoreOrderItems(ORDER_ID, MERCHANT_A)).thenReturn(0L);

        BusinessException exception = assertThrows(
                BusinessException.class, () -> service.deliver(ORDER_NO));

        assertEquals(ResultStatus.MERCHANT_ORDER_FORBIDDEN, exception.getStatus());
        verify(logisticsService, never()).markDeliveredForMerchant(anyLong());
    }

    @Test
    void ship_shouldRejectUnknownOrderNo() {
        when(orderMapper.selectOne(any())).thenReturn(null);

        BusinessException exception = assertThrows(
                BusinessException.class, () -> service.ship(ORDER_NO, new ShipOrderDTO()));

        assertEquals(ResultStatus.ORDER_NOT_EXIST, exception.getStatus());
    }
}
