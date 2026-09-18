package com.mall.module.admin.entity.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AdminLoginDTO {

    @NotBlank
    private String username;

    @NotBlank
    private String password;

}
