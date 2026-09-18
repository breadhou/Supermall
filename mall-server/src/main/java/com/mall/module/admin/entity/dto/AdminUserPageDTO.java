package com.mall.module.admin.entity.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class AdminUserPageDTO {

    @Min(1)
    private Integer pageNum = 1;

    @Min(1)
    @Max(100)
    private Integer pageSize = 20;

    /** 可空，按用户名模糊筛选。 */
    private String keyword;

    /** 可空，1=正常 0=禁用。 */
    private Integer status;

}
