package com.mall.module.seckill.entity.vo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

/**
 * Per-user snapshot created by the path endpoint.  Keeping the address and
 * purchase data here means the execute endpoint remains database-free.
 */
@Data
@Accessors(chain = true)
public class SeckillRequestSnapshot {

    private String path;
    private Long skuId;
    private BigDecimal seckillPrice;
    private Integer limitPerUser;
    private Long addressId;
}
