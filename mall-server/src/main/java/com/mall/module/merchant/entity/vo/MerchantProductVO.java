package com.mall.module.merchant.entity.vo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Accessors(chain = true)
public class MerchantProductVO {

    private Long id;
    private Long merchantId;
    private String name;
    private String description;
    private Long categoryId;
    private String status;
    private LocalDateTime createdAt;

    private List<SkuVO> skus;

    @Data
    @Accessors(chain = true)
    public static class SkuVO {

        private Long id;
        private String specs;
        private BigDecimal price;
        private Integer stock;
        private String image;

    }
}
