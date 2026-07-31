package com.mall.module.order.entity.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RefundDTO {

    @NotBlank
    private String reason;

}
