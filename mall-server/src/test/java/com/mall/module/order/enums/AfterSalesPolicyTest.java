package com.mall.module.order.enums;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void resolve_shouldHonorSevenDayBoundary() {
        // Task 4 调用的是 resolve 而不是 appliesTo，边界必须在这一层也锁住
        assertEquals(AfterSalesPolicy.SEVEN_DAY_NO_REASON,
                AfterSalesPolicy.resolve("RECEIVED", 7));
        assertEquals(AfterSalesPolicy.QUALITY_ISSUE,
                AfterSalesPolicy.resolve("RECEIVED", 8));
    }

    @Test
    void resolve_shouldReturnNullWhenNothingApplies() {
        assertNull(AfterSalesPolicy.resolve("PAID", 1));
        assertNull(AfterSalesPolicy.resolve("PENDING", 1));
        assertNull(AfterSalesPolicy.resolve("CANCELLED", 1));
    }

    /**
     * 每个常量都必须能被 {@link AfterSalesPolicy#resolve} 返回。
     *
     * <p>被遮蔽的常量比死代码更危险：Task 7 会把 {@code values()} 全量索引进 RAG，
     * 它的条款文本照样能被检索到，于是「判定永不命中、文本却说适用」。
     * 兜底项 {@link AfterSalesPolicy#QUALITY_ISSUE} 匹配所有 RECEIVED，
     * 任何追加在其后的 RECEIVED 政策都会被它吃掉——这个测试就是拦这件事。</p>
     *
     * <p>网格搜索是「存在性」判定：常量只要在任一格被返回就算可达，所以不会漏报。
     * 反向的误报是可能的——新增政策若只在网格外的状态或天数生效，会被当成遮蔽，
     * 届时把那个状态或天数补进下面的网格即可。宁可误红，也不要静默放行。</p>
     */
    @Test
    void everyPolicyIsReachableThroughResolve() {
        String[] statuses = {"PENDING", "PAID", "SHIPPED", "DELIVERED", "RECEIVED", "CANCELLED"};
        long[] days = {-1, 0, 1, 7, 8, 30, 365};

        EnumSet<AfterSalesPolicy> reachable = EnumSet.noneOf(AfterSalesPolicy.class);
        for (String status : statuses) {
            for (long day : days) {
                AfterSalesPolicy hit = AfterSalesPolicy.resolve(status, day);
                if (hit != null) {
                    reachable.add(hit);
                }
            }
        }

        for (AfterSalesPolicy policy : AfterSalesPolicy.values()) {
            assertTrue(reachable.contains(policy),
                    policy + " 永远不会被 resolve 返回：它被声明顺序靠前的兜底政策遮蔽了，"
                            + "但它的条款文本仍会被索引进 RAG，造成判定与文本矛盾");
        }
        assertEquals(EnumSet.allOf(AfterSalesPolicy.class), reachable,
                "可达集合必须与 values() 完全一致，多出来的常量即被遮蔽");
    }
}
