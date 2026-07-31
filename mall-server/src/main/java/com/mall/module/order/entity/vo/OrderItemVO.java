package com.mall.module.order.entity.vo;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class OrderItemVO {

    private Long id;
    private Long skuId;
    /** 下单时的价格快照，不是 SKU 当前价 */
    private BigDecimal price;
    private Integer quantity;
    /** SKU 规格 */
    private String specs;
    /** SKU 图片 */
    private String image;
    /** 商品名称 */
    private String productName;

}
