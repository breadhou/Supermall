package com.mall.module.user.entity.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AddressDTO {

    @NotBlank
    private String receiver;

    @NotBlank
    private String phone;

    @NotBlank
    private String province;

    @NotBlank
    private String city;

    @NotBlank
    private String district;

    @NotBlank
    private String detail;

    private Integer isDefault;

}
