package com.mall.module.merchant.entity.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/** 后台账号。{@code merchantId} 为空表示平台账号，非空表示某商家的账号。 */
@Data
@TableName("admin_user")
@Accessors(chain = true)
public class AdminUser {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String username;
    private String password;
    private String role;
    private Long merchantId;
    private LocalDateTime createdAt;

}
