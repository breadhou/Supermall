package com.mall.module.merchant.entity.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class MerchantLoginDTO {

    @NotBlank
    private String username;

    @NotBlank
    private String password;

}
