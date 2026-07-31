package com.mall.module.order.entity.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class CreateOrderDTO {

    @NotNull
    private Long addressId;

    @NotEmpty
    @Valid
    private List<OrderItemDTO> items;

    private Long couponId;

    @Data
    public static class OrderItemDTO {

        @NotNull
        private Long skuId;

        @Min(1)
        private Integer quantity;

    }
}
