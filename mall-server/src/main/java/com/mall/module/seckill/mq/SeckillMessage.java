package com.mall.module.seckill.mq;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

@Data
@Accessors(chain = true)
public class SeckillMessage {
    private Long userId;
    private Long seckillItemId;
    private String messageId;
    private Integer quantity;

    /** Purchase snapshot populated before publishing; avoids hot consumer lookups. */
    private Long skuId;
    private BigDecimal seckillPrice;
    private Long addressId;
}
