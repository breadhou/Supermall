package com.mall.module.coupon.entity.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;

/** Result of validating and atomically consuming one user coupon. */
@Data
@AllArgsConstructor
public class CouponApplyResult {

    private BigDecimal originalAmount;
    private BigDecimal discountAmount;
    private BigDecimal finalAmount;
}
