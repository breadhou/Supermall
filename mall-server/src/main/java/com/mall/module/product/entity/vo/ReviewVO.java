package com.mall.module.product.entity.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ReviewVO {

    private Long id;
    private Long userId;
    private Long productId;
    private Long orderId;
    private Integer rating;
    private String content;
    private LocalDateTime createdAt;

}
