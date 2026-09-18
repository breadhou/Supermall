package com.mall.module.merchant.entity.vo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

/** 本店在某张订单里的明细行。{@code orderId} 仅用于服务层分组，不对外暴露。 */
@Data
@Accessors(chain = true)
public class MerchantOrderItemVO {

    private Long orderId;
    private Long skuId;
    private BigDecimal price;
    private Integer quantity;
    private String specs;
    private String image;
    private String productName;

}
