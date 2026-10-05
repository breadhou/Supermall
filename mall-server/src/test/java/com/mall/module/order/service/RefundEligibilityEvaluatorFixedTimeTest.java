package com.mall.module.order.service;

import com.mall.module.order.entity.po.Order;
import com.mall.module.order.enums.AfterSalesPolicy;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

/** Test-only fixed-clock inputs; expected policies are independent literal assertions. */
class RefundEligibilityEvaluatorFixedTimeTest {
    private static final LocalDateTime EVALUATED = LocalDateTime.of(2026, 10, 1, 10, 0);

    static List<RefundEligibilityEvaluator.Assessment> assessWindow(Order fixture) {
        LocalDateTime insideCreated = LocalDateTime.of(2026, 9, 23, 10, 0, 1);
        LocalDateTime outsideCreated = LocalDateTime.of(2026, 9, 23, 10, 0);
        Order inside = new Order().setStatus(fixture.getStatus()).setTotalAmount(fixture.getTotalAmount()).setCreatedAt(insideCreated);
        Order outside = new Order().setStatus(fixture.getStatus()).setTotalAmount(fixture.getTotalAmount()).setCreatedAt(outsideCreated);
        RefundEligibilityEvaluator evaluator = new RefundEligibilityEvaluator();
        try (MockedStatic<LocalDateTime> clock = mockStatic(LocalDateTime.class, CALLS_REAL_METHODS)) {
            clock.when(LocalDateTime::now).thenReturn(EVALUATED);
            return List.of(evaluator.assess(inside), evaluator.assess(outside));
        }
    }

    static void assertWindow(List<RefundEligibilityEvaluator.Assessment> assessments, BigDecimal paid) {
        assertEquals(AfterSalesPolicy.SEVEN_DAY_NO_REASON, assessments.get(0).policy());
        assertEquals(AfterSalesPolicy.QUALITY_ISSUE, assessments.get(1).policy());
        for (RefundEligibilityEvaluator.Assessment assessment : assessments) {
            assertEquals("RECEIVED", assessment.orderStatus());
            assertEquals(0, paid.compareTo(assessment.refundableAmount()));
        }
    }

    @Test
    void realEvaluatorSeparatesLastSecondOfDaySevenFromExactDayEight() {
        Order order = new Order().setStatus("RECEIVED").setTotalAmount(new BigDecimal("39.80"));
        assertWindow(assessWindow(order), new BigDecimal("39.80"));
    }
}
