package com.mall.module.admin.entity.vo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/** 用户管理列表项。刻意不含 password 字段。 */
@Data
@Accessors(chain = true)
public class AdminUserVO {

    private Long id;
    private String username;
    private String phone;
    private String email;
    private Integer status;
    private LocalDateTime createdAt;

}
