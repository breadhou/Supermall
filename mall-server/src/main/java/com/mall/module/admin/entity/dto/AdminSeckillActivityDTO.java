package com.mall.module.admin.entity.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 创建秒杀活动。活动本身不参与秒杀，真正被抢的是活动下的秒杀商品，
 * 因此商品列表必填——只建一个空活动没有意义。
 */
@Data
public class AdminSeckillActivityDTO {

    @NotBlank
    @Size(max = 128)
    private String name;

    @NotNull
    private LocalDateTime startTime;

    @NotNull
    private LocalDateTime endTime;

    @NotEmpty
    @Valid
    private List<ItemDTO> items;

    @Data
    public static class ItemDTO {

        @NotNull
        private Long skuId;

        @NotNull
        @DecimalMin("0.01")
        private BigDecimal seckillPrice;

        @NotNull
        @Min(0)
        private Integer stock;

        @NotNull
        @Min(1)
        private Integer limitPerUser;

    }
}
