package com.mall.module.seckill.entity.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("seckill_item")
@Accessors(chain = true)
public class SeckillItem {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long activityId;
    private Long skuId;
    /** 秒杀价格 */
    private BigDecimal seckillPrice;
    /** 秒杀专用库存，独立于 product_sku.stock */
    private Integer stock;
    /** 每人限购数量 */
    private Integer limitPerUser;
    private LocalDateTime createdAt;

}
