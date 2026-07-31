package com.mall.module.cart.entity.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UpdateCartItemDTO {

    @NotNull
    @Min(1)
    @Max(999)
    private Integer quantity;

}
