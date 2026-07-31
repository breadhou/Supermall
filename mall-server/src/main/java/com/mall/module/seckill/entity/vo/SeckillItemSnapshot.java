package com.mall.module.seckill.entity.vo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

/**
 * Immutable-at-runtime item data used by the seckill hot path and consumer.
 * It is populated during stock preheating and never read from MySQL by the
 * execute endpoint.
 */
@Data
@Accessors(chain = true)
public class SeckillItemSnapshot {

    private Long itemId;
    private Long skuId;
    private BigDecimal seckillPrice;
    private Integer limitPerUser;
}
