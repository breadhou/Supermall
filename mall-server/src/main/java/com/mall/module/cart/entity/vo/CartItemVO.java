package com.mall.module.cart.entity.vo;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class CartItemVO {

    private Long id;
    private Long skuId;
    private Integer quantity;
    /** SKU 规格 */
    private String specs;
    /** SKU 单价 */
    private BigDecimal price;
    /** SKU 库存 */
    private Integer stock;
    /** SKU 图片 */
    private String image;
    /** 商品名称 */
    private String productName;

}
