package com.mall.module.seckill.entity.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@TableName("seckill_order")
@Accessors(chain = true)
public class SeckillOrder {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long userId;
    private Long seckillItemId;
    private Long orderId;
    private LocalDateTime createdAt;

}
