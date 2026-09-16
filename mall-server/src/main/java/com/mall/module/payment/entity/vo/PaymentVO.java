package com.mall.module.payment.entity.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class PaymentVO {

    private Long id;
    private Long orderId;
    private BigDecimal amount;
    private String method;
    private String status;
    private LocalDateTime paidAt;
    private LocalDateTime createdAt;

}
