package com.mall.module.order.service.impl;

import com.mall.common.utils.SnowflakeIdUtil;
import com.mall.module.order.entity.po.Order;
import com.mall.module.order.entity.po.Refund;
import com.mall.module.order.entity.vo.PolicyCatalogVO;
import com.mall.module.order.entity.vo.RefundEligibilityVO;
import com.mall.module.order.controller.AfterSalesPolicyController;
import com.mall.module.order.enums.AfterSalesPolicy;
import com.mall.module.order.mapper.OrderMapper;
import com.mall.module.order.mapper.RefundMapper;
import com.mall.module.order.service.AfterSalesPolicyCatalog;
import com.mall.security.utils.UserContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
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

        service = new RefundEligibilityServiceImpl(orderMapper, refundMapper,
                new AfterSalesPolicyCatalog());
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
    void readAndLockedWriteUseSamePolicyEvaluator() {
        Order readOrder = order("SHIPPED", LocalDateTime.now().minusDays(2));
        Order lockedOrder = order("SHIPPED", LocalDateTime.now().minusDays(2))
                .setTotalAmount(new BigDecimal("199.990"));
        when(orderMapper.selectById(ORDER_ID)).thenReturn(readOrder, readOrder);
        when(orderMapper.selectByIdForUpdate(ORDER_ID)).thenReturn(lockedOrder);
        when(refundMapper.selectOne(any())).thenReturn(null, null, null);

        try (MockedStatic<SnowflakeIdUtil> snowflakeIdUtil = mockStatic(SnowflakeIdUtil.class)) {
            snowflakeIdUtil.when(SnowflakeIdUtil::nextId).thenReturn(9100L);
            RefundEligibilityVO read = service.check(ORDER_ID);
            RefundExecutionServiceImpl writer =
                    new RefundExecutionServiceImpl(orderMapper, refundMapper, service);

            RefundEligibilityVO write = writer.execute(ORDER_ID, "测试理由");

            assertEquals(AfterSalesPolicy.SHIPPED_NOT_RECEIVED.name(), read.getPolicyCode());
            assertEquals(read.getPolicyCode(), write.getPolicyCode());
            assertEquals(0, read.getRefundableAmount().compareTo(write.getRefundableAmount()));
            org.mockito.ArgumentCaptor<Refund> refundCaptor =
                    org.mockito.ArgumentCaptor.forClass(Refund.class);
            verify(refundMapper).insert(refundCaptor.capture());
            assertEquals(0, refundCaptor.getValue().getAmount().compareTo(new BigDecimal("199.99")));
        }
    }

    @Test
    void eligibleAndIneligibleResultsCarryCurrentCatalogFingerprint() {
        PolicyCatalogVO snapshot = new AfterSalesPolicyController(
                new AfterSalesPolicyCatalog()).listPolicies().getData();
        Order eligibleOrder = order("RECEIVED", 2);
        Order deniedOrder = order("PENDING", 1);
        Order existingRefundOrder = order("SHIPPED", 1);
        when(orderMapper.selectById(ORDER_ID))
                .thenReturn(eligibleOrder, deniedOrder, existingRefundOrder);
        when(refundMapper.selectOne(any()))
                .thenReturn(null, null,
                        new Refund().setId(1L).setOrderId(ORDER_ID).setStatus("PENDING"));

        RefundEligibilityVO eligible = service.check(ORDER_ID);
        RefundEligibilityVO denied = service.check(ORDER_ID);
        RefundEligibilityVO existingRefund = service.check(ORDER_ID);

        assertTrue(eligible.isEligible());
        assertFalse(denied.isEligible());
        assertTrue(existingRefund.isRefundExists());
        assertFalse(existingRefund.isEligible());
        assertAll(
                () -> assertCarriesSnapshotAndStatus(snapshot, eligibleOrder, eligible),
                () -> assertCarriesSnapshotAndStatus(snapshot, deniedOrder, denied),
                () -> assertCarriesSnapshotAndStatus(snapshot, existingRefundOrder, existingRefund));
    }

    private static void assertCarriesSnapshotAndStatus(
            PolicyCatalogVO snapshot, Order order, RefundEligibilityVO eligibility) {
        JsonNode json = new ObjectMapper().valueToTree(eligibility);
        String fingerprint = json.path("catalogFingerprint").asText(null);

        assertAll(
                () -> assertEquals(snapshot.getFingerprint(), fingerprint),
                () -> assertNotNull(fingerprint),
                () -> assertTrue(fingerprint != null && fingerprint.matches("[0-9a-f]{64}")),
                () -> assertEquals(order.getStatus(), json.path("orderStatus").asText(null)));
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
