package com.mall.module.product.entity.dto;

import lombok.Data;

@Data
public class ProductPageDTO {

    private Integer pageNum = 1;

    private Integer pageSize = 20;

    private String keyword;
    private Long categoryId;
    private String status = "ON_SHELF";

}
