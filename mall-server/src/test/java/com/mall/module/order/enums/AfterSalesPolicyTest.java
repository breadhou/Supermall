package com.mall.module.order.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AfterSalesPolicyTest {

    @Test
    void everyPolicyCarriesBothRuleAndClauseText() {
        for (AfterSalesPolicy policy : AfterSalesPolicy.values()) {
            assertFalse(policy.getClauseText().isBlank(),
                    policy + " 缺少条款文本，RAG 将无据可依");
            assertFalse(policy.getTitle().isBlank(), policy + " 缺少标题");
        }
    }

    @Test
    void sevenDayPolicy_shouldRejectAfterWindow() {
        // 签收后 8 天，超出 7 天窗口
        assertFalse(AfterSalesPolicy.SEVEN_DAY_NO_REASON.appliesTo("RECEIVED", 8));
        assertTrue(AfterSalesPolicy.SEVEN_DAY_NO_REASON.appliesTo("RECEIVED", 7));
        assertTrue(AfterSalesPolicy.SEVEN_DAY_NO_REASON.appliesTo("RECEIVED", 0));
    }

    @Test
    void sevenDayPolicy_shouldNotApplyBeforeReceipt() {
        assertFalse(AfterSalesPolicy.SEVEN_DAY_NO_REASON.appliesTo("SHIPPED", 1));
        assertFalse(AfterSalesPolicy.SEVEN_DAY_NO_REASON.appliesTo("PAID", 1));
    }

    @Test
    void shippedNotReceived_shouldOnlyApplyToShippedOrDelivered() {
        assertTrue(AfterSalesPolicy.SHIPPED_NOT_RECEIVED.appliesTo("SHIPPED", 3));
        assertTrue(AfterSalesPolicy.SHIPPED_NOT_RECEIVED.appliesTo("DELIVERED", 3));
        assertFalse(AfterSalesPolicy.SHIPPED_NOT_RECEIVED.appliesTo("RECEIVED", 3));
        assertFalse(AfterSalesPolicy.SHIPPED_NOT_RECEIVED.appliesTo("PAID", 3));
    }

    @Test
    void qualityIssue_shouldApplyToReceivedOrdersWithoutTimeLimit() {
        assertTrue(AfterSalesPolicy.QUALITY_ISSUE.appliesTo("RECEIVED", 100));
        assertFalse(AfterSalesPolicy.QUALITY_ISSUE.appliesTo("SHIPPED", 1));
    }

    @Test
    void resolve_shouldReturnFirstMatchingPolicyInPriorityOrder() {
        // 签收 2 天：7天无理由 与 质量问题 都成立，取优先级更高的 7天无理由
        assertEquals(AfterSalesPolicy.SEVEN_DAY_NO_REASON,
                AfterSalesPolicy.resolve("RECEIVED", 2));
        // 签收 30 天：只有质量问题成立
        assertEquals(AfterSalesPolicy.QUALITY_ISSUE,
                AfterSalesPolicy.resolve("RECEIVED", 30));
        // 已发货未签收
        assertEquals(AfterSalesPolicy.SHIPPED_NOT_RECEIVED,
                AfterSalesPolicy.resolve("SHIPPED", 1));
    }

    @Test
    void resolve_shouldReturnNullWhenNothingApplies() {
        assertEquals(null, AfterSalesPolicy.resolve("PAID", 1));
        assertEquals(null, AfterSalesPolicy.resolve("PENDING", 1));
        assertEquals(null, AfterSalesPolicy.resolve("CANCELLED", 1));
    }
}
