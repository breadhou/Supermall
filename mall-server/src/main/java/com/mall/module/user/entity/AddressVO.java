package com.mall.module.user.entity;

import com.mall.common.enums.UserStatus;
import lombok.Data;

@Data
public class AddressVO {

    private Long id;
    private Long userId;
    private String receiver;
    private String phone;
    private String province;
    private String city;
    private String district;
    private String detail;
    private UserStatus isDefault;

}
