package com.mall.module.user.entity.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@TableName("address")
@Accessors(chain = true)
public class Address {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long userId;
    private String receiver;
    private String phone;
    private String province;
    private String city;
    private String district;
    private String detail;
    /**
     * 1=默认地址，0=非默认。与 DTO、VO 及数据库 TINYINT 列保持一致。
     *
     * <p>此处若用 {@link Boolean}，{@code BeanUtils.copyProperties} 会因为
     * 与 DTO({@code Integer}) 类型不匹配而静默跳过该字段，导致默认地址
     * 永远无法被设置。</p>
     */
    private Integer isDefault;
    private LocalDateTime createdAt;

}
