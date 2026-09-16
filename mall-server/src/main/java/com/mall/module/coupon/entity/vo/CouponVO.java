package com.mall.module.coupon.entity.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class CouponVO {

    private Long id;
    private String name;
    private String type;
    private BigDecimal discount;
    private BigDecimal minAmount;
    private Integer total;
    private Long remaining;
    private Integer expireDay;
    private LocalDateTime createdAt;
}
