package com.mall.module.coupon.entity.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@TableName("user_coupon")
@Accessors(chain = true)
public class UserCoupon {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long userId;
    private Long couponId;
    private String status;
    private LocalDateTime usedAt;
    private LocalDateTime createdAt;
}
