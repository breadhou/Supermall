package com.mall.module.user.entity;

import lombok.Data;

@Data
public class LoginVO {
    private String accessToken;
    private String refreshToken;
    private Long id;
    private String username;
}
