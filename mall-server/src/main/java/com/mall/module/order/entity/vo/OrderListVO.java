package com.mall.module.order.entity.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class OrderListVO {

    private Long id;
    private String orderNo;
    private BigDecimal totalAmount;
    private String status;
    private LocalDateTime createdAt;
    /** 订单项个数 */
    private Integer itemCount;

}
