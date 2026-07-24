package com.mall.module.product.entity.vo;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class ProductSkuVO {

    private Long id;
    private String specs;
    private BigDecimal price;
    private Integer stock;
    private String image;

}
