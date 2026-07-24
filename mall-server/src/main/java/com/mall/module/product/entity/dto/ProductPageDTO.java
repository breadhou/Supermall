package com.mall.module.product.entity.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ProductPageDTO {

    @NotNull
    private Integer pageNum = 1;

    @NotNull
    private Integer pageSize = 20;

    private String keyword;
    private Long categoryId;
    private String status = "ON_SHELF";

}
