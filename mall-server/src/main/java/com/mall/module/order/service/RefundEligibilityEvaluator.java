package com.mall.module.order.service;

import com.mall.module.order.entity.po.Order;
import com.mall.module.order.enums.AfterSalesPolicy;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;

/** Resolves the same order facts and policy for eligibility reads and locked writes. */
@Component
public class RefundEligibilityEvaluator {

    public Assessment assess(Order order) {
        LocalDateTime createdAt = order.getCreatedAt();
        long completeDays = createdAt == null ? 0
                : Duration.between(createdAt, LocalDateTime.now()).toDays();
        return new Assessment(order.getStatus(), order.getTotalAmount(),
                AfterSalesPolicy.resolve(order.getStatus(), completeDays));
    }

    public record Assessment(String orderStatus, BigDecimal refundableAmount,
                             AfterSalesPolicy policy) {
    }
}
