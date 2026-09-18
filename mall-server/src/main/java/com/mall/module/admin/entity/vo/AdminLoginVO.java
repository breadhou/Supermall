package com.mall.module.admin.entity.vo;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class AdminLoginVO {

    private String accessToken;
    private Long adminUserId;
    private String username;
    private String role;

}
