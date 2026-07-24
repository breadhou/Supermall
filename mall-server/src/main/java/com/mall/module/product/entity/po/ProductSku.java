package com.mall.module.product.entity.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("product_sku")
@Accessors(chain = true)
public class ProductSku {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long productId;
    private String specs;
    private BigDecimal price;
    private Integer stock;
    private String image;
    private LocalDateTime createdAt;

}
