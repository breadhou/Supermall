package com.mall.module.merchant.entity.vo;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class MerchantLoginVO {

    private String accessToken;
    private Long adminUserId;
    private Long merchantId;
    private String merchantName;
    private String role;

}
