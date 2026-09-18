package com.mall.module.admin.entity.vo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
public class CouponTemplateVO {

    private Long id;
    private String name;
    private String type;
    private BigDecimal discount;
    private BigDecimal minAmount;
    private Integer total;
    private Integer expireDay;
    private LocalDateTime createdAt;

}
