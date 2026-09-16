package com.mall.module.logistics.service.impl;

import com.mall.common.enums.ResultStatus;
import com.mall.common.exception.BusinessException;
import com.mall.common.utils.SnowflakeIdUtil;
import com.mall.module.logistics.entity.dto.ShipOrderDTO;
import com.mall.module.logistics.entity.po.Logistics;
import com.mall.module.logistics.mapper.LogisticsMapper;
import com.mall.module.order.entity.po.Order;
import com.mall.module.order.mapper.OrderMapper;
import com.mall.security.utils.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LogisticsServiceImplTest {

    @Mock
    private LogisticsMapper logisticsMapper;

    @Mock
    private OrderMapper orderMapper;

    @InjectMocks
    private LogisticsServiceImpl logisticsService;

    private MockedStatic<UserContext> userContextMock;
    private MockedStatic<SnowflakeIdUtil> snowflakeIdUtilMock;

    private static final Long USER_ID = 1001L;
    private static final Long ORDER_ID = 9001L;

    @BeforeEach
    void setUp() {
        userContextMock = org.mockito.Mockito.mockStatic(UserContext.class);
        snowflakeIdUtilMock = org.mockito.Mockito.mockStatic(SnowflakeIdUtil.class);
        userContextMock.when(UserContext::getUserId).thenReturn(USER_ID);
        snowflakeIdUtilMock.when(SnowflakeIdUtil::nextId).thenReturn(8001L);
    }

    @AfterEach
    void tearDown() {
        userContextMock.close();
        snowflakeIdUtilMock.close();
    }

    @Test
    void shipOrder_shouldCreateShipmentAndMarkOrderShipped() {
        Order order = order("PAID");
        when(orderMapper.selectByIdForUpdate(ORDER_ID)).thenReturn(order);
        ShipOrderDTO dto = shipOrderDTO();

        var result = logisticsService.shipOrder(ORDER_ID, dto);

        assertEquals(ORDER_ID, result.getOrderId());
        assertEquals("SF", result.getCompany());
        assertEquals("SF123", result.getTrackingNo());
        assertEquals("SHIPPED", result.getStatus());
        assertEquals("SHIPPED", order.getStatus());
        verify(logisticsMapper).insert(any(Logistics.class));
        verify(orderMapper).updateById(order);
    }

    @Test
    void shipOrder_shouldRejectDuplicateShipment() {
        Order order = order("PAID");
        when(orderMapper.selectByIdForUpdate(ORDER_ID)).thenReturn(order);
        when(logisticsMapper.selectByOrderIdForUpdate(ORDER_ID)).thenReturn(logistics("SHIPPED"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> logisticsService.shipOrder(ORDER_ID, shipOrderDTO()));

        assertEquals(ResultStatus.LOGISTICS_STATUS_ERROR, exception.getStatus());
        verify(logisticsMapper, never()).insert(any(Logistics.class));
        verify(orderMapper, never()).updateById(any(Order.class));
    }

    @Test
    void shipOrder_shouldRejectUnpaidOrder() {
        when(orderMapper.selectByIdForUpdate(ORDER_ID)).thenReturn(order("PENDING"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> logisticsService.shipOrder(ORDER_ID, shipOrderDTO()));

        assertEquals(ResultStatus.LOGISTICS_STATUS_ERROR, exception.getStatus());
        verify(logisticsMapper, never()).insert(any(Logistics.class));
    }

    @Test
    void markDelivered_shouldUpdateLogisticsAndOrder() {
        Order order = order("SHIPPED");
        Logistics logistics = logistics("SHIPPED");
        when(orderMapper.selectByIdForUpdate(ORDER_ID)).thenReturn(order);
        when(logisticsMapper.selectByOrderIdForUpdate(ORDER_ID)).thenReturn(logistics);

        var result = logisticsService.markDelivered(ORDER_ID);

        assertEquals("DELIVERED", result.getStatus());
        assertEquals("DELIVERED", logistics.getStatus());
        assertEquals("DELIVERED", order.getStatus());
        verify(logisticsMapper).updateById(logistics);
        verify(orderMapper).updateById(order);
    }

    @Test
    void markDelivered_shouldRejectAlreadyDeliveredLogistics() {
        Order order = order("SHIPPED");
        when(orderMapper.selectByIdForUpdate(ORDER_ID)).thenReturn(order);
        when(logisticsMapper.selectByOrderIdForUpdate(ORDER_ID)).thenReturn(logistics("DELIVERED"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> logisticsService.markDelivered(ORDER_ID));

        assertEquals(ResultStatus.LOGISTICS_STATUS_ERROR, exception.getStatus());
        verify(logisticsMapper, never()).updateById(any(Logistics.class));
        verify(orderMapper, never()).updateById(any(Order.class));
    }

    @Test
    void getLogistics_shouldRejectWhenRecordDoesNotExist() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order("SHIPPED"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> logisticsService.getLogistics(ORDER_ID));

        assertEquals(ResultStatus.LOGISTICS_NOT_EXIST, exception.getStatus());
    }

    private Order order(String status) {
        return new Order().setId(ORDER_ID).setUserId(USER_ID).setStatus(status);
    }

    private ShipOrderDTO shipOrderDTO() {
        ShipOrderDTO dto = new ShipOrderDTO();
        dto.setCompany("SF");
        dto.setTrackingNo("SF123");
        return dto;
    }

    private Logistics logistics(String status) {
        return new Logistics()
                .setId(8001L)
                .setOrderId(ORDER_ID)
                .setCompany("SF")
                .setTrackingNo("SF123")
                .setStatus(status);
    }

}
