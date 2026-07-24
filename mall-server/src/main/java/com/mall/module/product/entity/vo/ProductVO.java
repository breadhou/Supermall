package com.mall.module.product.entity.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class ProductVO {

    private Long id;
    private String name;
    private String description;
    private Long categoryId;
    private String status;

    private BigDecimal minPrice;
    private String mainImage;
    private Integer totalStock;

    private List<ProductSkuVO> skus;

    private LocalDateTime createdAt;

}
