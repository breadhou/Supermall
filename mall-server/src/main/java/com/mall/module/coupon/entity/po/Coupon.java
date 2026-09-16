package com.mall.module.coupon.entity.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("coupon")
@Accessors(chain = true)
public class Coupon {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String name;
    /** FULL_REDUCTION or DISCOUNT. */
    private String type;
    /** Reduction amount for FULL_REDUCTION, discount factor (0~1) for DISCOUNT. */
    private BigDecimal discount;
    private BigDecimal minAmount;
    private Integer total;
    private Integer expireDay;
    private LocalDateTime createdAt;
}
