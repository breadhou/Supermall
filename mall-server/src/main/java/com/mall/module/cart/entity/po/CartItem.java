package com.mall.module.cart.entity.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@TableName("cart_item")
@Accessors(chain = true)
public class CartItem {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long userId;
    private Long skuId;
    private Integer quantity;

}
