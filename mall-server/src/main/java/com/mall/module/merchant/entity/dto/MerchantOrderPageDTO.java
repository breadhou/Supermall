package com.mall.module.merchant.entity.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class MerchantOrderPageDTO {

    @Min(1)
    private Integer pageNum = 1;

    @Min(1)
    @Max(100)
    private Integer pageSize = 20;

    /** 可空，按订单状态筛选。 */
    private String status;

}
