package com.mall.module.order.service.impl;

import com.mall.module.order.entity.po.Order;
import com.mall.module.order.entity.po.Refund;
import com.mall.module.order.entity.vo.RefundEligibilityVO;
import com.mall.module.order.enums.AfterSalesPolicy;
import com.mall.module.order.mapper.OrderMapper;
import com.mall.module.order.mapper.RefundMapper;
import com.mall.security.utils.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefundEligibilityServiceImplTest {

    private static final Long USER_ID = 1001L;
    private static final Long ORDER_ID = 9001L;

    @Mock
    private OrderMapper orderMapper;
    @Mock
    private RefundMapper refundMapper;

    private RefundEligibilityServiceImpl service;

    private MockedStatic<UserContext> userContextMock;

    @BeforeEach
    void setUp() {
        // UserContext 是纯 ThreadLocal：单测里没有 JwtAuthFilter 跑过，
        // 不 mock 的话 getUserId() 返回 null，check() 第一行就 NPE。
        userContextMock = mockStatic(UserContext.class);
        userContextMock.when(UserContext::getUserId).thenReturn(USER_ID);

        service = new RefundEligibilityServiceImpl(orderMapper, refundMapper);
    }

    @AfterEach
    void tearDown() {
        if (userContextMock != null) userContextMock.close();
    }

    private Order order(String status, int daysAgo) {
        return new Order()
                .setId(ORDER_ID)
                .setUserId(USER_ID)
                .setStatus(status)
                .setTotalAmount(new BigDecimal("199.99"))
                .setCreatedAt(LocalDateTime.now().minusDays(daysAgo));
    }

    @Test
    void shouldBeEligibleWithinSevenDays() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order("RECEIVED", 2));
        when(refundMapper.selectOne(any())).thenReturn(null);

        RefundEligibilityVO vo = service.check(ORDER_ID);

        assertTrue(vo.isEligible());
        assertEquals(AfterSalesPolicy.SEVEN_DAY_NO_REASON.name(), vo.getPolicyCode());
        assertEquals(new BigDecimal("199.99"), vo.getRefundableAmount());
        assertFalse(vo.isRefundExists());
    }

    @Test
    void shouldBeIneligibleAfterSevenDaysForNoReasonPolicy() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order("RECEIVED", 30));
        when(refundMapper.selectOne(any())).thenReturn(null);

        RefundEligibilityVO vo = service.check(ORDER_ID);

        // 30 天后 7 天无理由失效，但质量问题仍成立
        assertTrue(vo.isEligible());
        assertEquals(AfterSalesPolicy.QUALITY_ISSUE.name(), vo.getPolicyCode());
    }

    @Test
    void shouldBeIneligibleWhenOrderIsPending() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order("PENDING", 1));
        when(refundMapper.selectOne(any())).thenReturn(null);

        RefundEligibilityVO vo = service.check(ORDER_ID);

        assertFalse(vo.isEligible());
        assertNull(vo.getPolicyCode());
        assertNotNull(vo.getReason());
    }

    @Test
    void shouldReportExistingRefundAndRefuseToRefundAgain() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order("RECEIVED", 2));
        when(refundMapper.selectOne(any()))
                .thenReturn(new Refund().setId(1L).setOrderId(ORDER_ID).setStatus("PENDING"));

        RefundEligibilityVO vo = service.check(ORDER_ID);

        assertTrue(vo.isRefundExists());
        assertFalse(vo.isEligible(), "已有退款记录时不应再判为可退");
    }

    @Test
    void shouldRefundFullOrderAmountNotPartial() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order("SHIPPED", 1));
        when(refundMapper.selectOne(any())).thenReturn(null);

        RefundEligibilityVO vo = service.check(ORDER_ID);

        assertEquals(new BigDecimal("199.99"), vo.getRefundableAmount());
    }
}
