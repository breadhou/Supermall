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
        return order(status, LocalDateTime.now().minusDays(daysAgo));
    }

    private Order order(String status, LocalDateTime createdAt) {
        return new Order()
                .setId(ORDER_ID)
                .setUserId(USER_ID)
                .setStatus(status)
                .setTotalAmount(new BigDecimal("199.99"))
                .setCreatedAt(createdAt);
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
    void sevenDaysAndTwentyThreeHours_shouldStayInFirstPolicyBecauseOnlyCompleteDaysCount() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(
                order("RECEIVED", LocalDateTime.now().minusDays(7).minusHours(23)));
        when(refundMapper.selectOne(any())).thenReturn(null);

        RefundEligibilityVO vo = service.check(ORDER_ID);

        assertTrue(vo.isEligible());
        assertEquals(AfterSalesPolicy.SEVEN_DAY_NO_REASON.name(), vo.getPolicyCode());
        assertEquals("已签收（完整天数不超过 7）整单退款", vo.getPolicyTitle());
        assertEquals("订单状态为已签收时，系统从订单创建时间起每满 24 小时计 1 天，不足 24 小时的余数不计；"
                        + "计数不超过 7 天可申请整单退款，退款执行后立即完成。",
                AfterSalesPolicy.SEVEN_DAY_NO_REASON.getClauseText());
    }

    @Test
    void eightCompleteDays_shouldMoveToReceivedFallbackPolicy() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(
                order("RECEIVED", LocalDateTime.now().minusDays(8)));
        when(refundMapper.selectOne(any())).thenReturn(null);

        RefundEligibilityVO vo = service.check(ORDER_ID);

        assertTrue(vo.isEligible());
        assertEquals(AfterSalesPolicy.QUALITY_ISSUE.name(), vo.getPolicyCode());
        assertEquals("已签收（完整天数超过 7）整单退款", vo.getPolicyTitle());
        assertEquals("订单状态为已签收时，系统从订单创建时间起每满 24 小时计 1 天，不足 24 小时的余数不计；"
                        + "计数超过 7 天仍可申请整单退款，退款执行后立即完成。",
                AfterSalesPolicy.QUALITY_ISSUE.getClauseText());
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
