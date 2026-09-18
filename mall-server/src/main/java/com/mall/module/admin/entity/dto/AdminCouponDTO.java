package com.mall.module.admin.entity.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class AdminCouponDTO {

    @NotBlank
    @Size(max = 128)
    private String name;

    /** FULL_REDUCTION=满减（discount 为金额）/ DISCOUNT=折扣（discount 为 0~1 的比例）。 */
    @NotBlank
    private String type;

    @NotNull
    @DecimalMin("0.01")
    private BigDecimal discount;

    @DecimalMin("0.00")
    private BigDecimal minAmount = BigDecimal.ZERO;

    @NotNull
    @Min(1)
    private Integer total;

    @NotNull
    @Min(1)
    private Integer expireDay;

}
