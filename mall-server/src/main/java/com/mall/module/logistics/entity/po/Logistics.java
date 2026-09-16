package com.mall.module.logistics.entity.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@TableName("logistics")
@Accessors(chain = true)
public class Logistics {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long orderId;
    private String company;
    private String trackingNo;
    private String status;
    private LocalDateTime createdAt;

}
