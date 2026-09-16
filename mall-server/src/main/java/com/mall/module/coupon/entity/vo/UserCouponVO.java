package com.mall.module.coupon.entity.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class UserCouponVO {

    private Long id;
    private Long couponId;
    private String name;
    private String type;
    private BigDecimal discount;
    private BigDecimal minAmount;
    private String status;
    private LocalDateTime usedAt;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
}
