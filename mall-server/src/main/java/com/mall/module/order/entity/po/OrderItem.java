package com.mall.module.order.entity.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

@Data
@TableName("order_item")
@Accessors(chain = true)
public class OrderItem {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long orderId;
    private Long skuId;
    /** 下单时的价格快照，后续 SKU 涨价不影响历史订单 */
    private BigDecimal price;
    private Integer quantity;

}
