package com.mall.module.payment.service.impl;

import com.mall.common.enums.ResultStatus;
import com.mall.common.exception.BusinessException;
import com.mall.common.utils.SnowflakeIdUtil;
import com.mall.module.order.entity.po.Order;
import com.mall.module.order.mapper.OrderMapper;
import com.mall.module.payment.entity.po.PaymentRecord;
import com.mall.module.payment.mapper.PaymentRecordMapper;
import com.mall.security.utils.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceImplTest {

    @Mock
    private PaymentRecordMapper paymentRecordMapper;

    @Mock
    private OrderMapper orderMapper;

    @InjectMocks
    private PaymentServiceImpl paymentService;

    private MockedStatic<UserContext> userContextMock;
    private MockedStatic<SnowflakeIdUtil> snowflakeIdUtilMock;

    private static final Long USER_ID = 1001L;
    private static final Long ORDER_ID = 9001L;

    @BeforeEach
    void setUp() {
        userContextMock = org.mockito.Mockito.mockStatic(UserContext.class);
        snowflakeIdUtilMock = org.mockito.Mockito.mockStatic(SnowflakeIdUtil.class);
        userContextMock.when(UserContext::getUserId).thenReturn(USER_ID);
        snowflakeIdUtilMock.when(SnowflakeIdUtil::nextId).thenReturn(7001L);
    }

    @AfterEach
    void tearDown() {
        userContextMock.close();
        snowflakeIdUtilMock.close();
    }

    @Test
    void pay_shouldCreateSuccessPaymentAndMarkOrderPaid() {
        Order order = order("PENDING");
        when(orderMapper.selectByIdForUpdate(ORDER_ID)).thenReturn(order);

        var result = paymentService.pay(ORDER_ID);

        assertEquals(ORDER_ID, result.getOrderId());
        assertEquals(new BigDecimal("99.00"), result.getAmount());
        assertEquals("SIMULATED", result.getMethod());
        assertEquals("SUCCESS", result.getStatus());
        assertEquals("PAID", order.getStatus());
        verify(paymentRecordMapper).insert(any(PaymentRecord.class));
        verify(orderMapper).updateById(order);
    }

    @Test
    void pay_shouldReturnExistingSuccessPaymentIdempotently() {
        Order order = order("PAID");
        PaymentRecord payment = payment("SUCCESS");
        when(orderMapper.selectByIdForUpdate(ORDER_ID)).thenReturn(order);
        when(paymentRecordMapper.selectByOrderIdForUpdate(ORDER_ID)).thenReturn(payment);

        var result = paymentService.pay(ORDER_ID);

        assertEquals(payment.getId(), result.getId());
        assertEquals("SUCCESS", result.getStatus());
        verify(paymentRecordMapper, never()).insert(any(PaymentRecord.class));
        verify(paymentRecordMapper, never()).updateById(any(PaymentRecord.class));
        verify(orderMapper, never()).updateById(any(Order.class));
    }

    @Test
    void pay_shouldRejectOrderOwnedByAnotherUser() {
        Order order = order("PENDING").setUserId(9999L);
        when(orderMapper.selectByIdForUpdate(ORDER_ID)).thenReturn(order);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> paymentService.pay(ORDER_ID));

        assertEquals(ResultStatus.ORDER_NOT_EXIST, exception.getStatus());
        verify(paymentRecordMapper, never()).selectByOrderIdForUpdate(any(Long.class));
    }

    @Test
    void pay_shouldRejectNonPendingOrderWithoutSuccessPayment() {
        Order order = order("CANCELLED");
        when(orderMapper.selectByIdForUpdate(ORDER_ID)).thenReturn(order);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> paymentService.pay(ORDER_ID));

        assertEquals(ResultStatus.PAYMENT_STATUS_ERROR, exception.getStatus());
        verify(paymentRecordMapper).selectByOrderIdForUpdate(ORDER_ID);
        verify(paymentRecordMapper, never()).insert(any(PaymentRecord.class));
    }

    @Test
    void getPayment_shouldReturnOwnedPayment() {
        Order order = order("PAID");
        PaymentRecord payment = payment("SUCCESS");
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order);
        when(paymentRecordMapper.selectByOrderId(ORDER_ID)).thenReturn(payment);

        var result = paymentService.getPayment(ORDER_ID);

        assertEquals(payment.getId(), result.getId());
        assertEquals("SUCCESS", result.getStatus());
    }

    @Test
    void getPayment_shouldRejectWhenRecordDoesNotExist() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order("PENDING"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> paymentService.getPayment(ORDER_ID));

        assertEquals(ResultStatus.PAYMENT_NOT_EXIST, exception.getStatus());
    }

    private Order order(String status) {
        return new Order()
                .setId(ORDER_ID)
                .setUserId(USER_ID)
                .setTotalAmount(new BigDecimal("99.00"))
                .setStatus(status);
    }

    private PaymentRecord payment(String status) {
        return new PaymentRecord()
                .setId(7001L)
                .setOrderId(ORDER_ID)
                .setAmount(new BigDecimal("99.00"))
                .setMethod("SIMULATED")
                .setStatus(status);
    }

}
