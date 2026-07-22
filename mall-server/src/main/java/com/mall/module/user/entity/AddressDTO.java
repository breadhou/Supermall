package com.mall.module.user.entity;

import com.mall.common.enums.UserStatus;
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

    private UserStatus isDefault;

}
