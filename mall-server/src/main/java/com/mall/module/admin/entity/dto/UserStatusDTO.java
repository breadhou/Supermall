package com.mall.module.admin.entity.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UserStatusDTO {

    /** 1=正常 0=禁用。 */
    @NotNull
    private Integer status;

}
