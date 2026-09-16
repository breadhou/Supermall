package com.mall.module.payment.entity.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("payment_record")
@Accessors(chain = true)
public class PaymentRecord {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long orderId;
    private BigDecimal amount;
    private String method;
    private String status;
    private LocalDateTime paidAt;
    private LocalDateTime createdAt;

}
