package com.mall.module.merchant.entity.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/** 商家。{@code status}：1=正常，0=禁用。 */
@Data
@TableName("merchant")
@Accessors(chain = true)
public class Merchant {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String name;
    private String contact;
    private Integer status;
    private LocalDateTime createdAt;

}
