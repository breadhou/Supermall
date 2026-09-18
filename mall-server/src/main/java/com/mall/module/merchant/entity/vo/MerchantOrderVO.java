package com.mall.module.merchant.entity.vo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 本店订单视图。
 *
 * <p>{@code storeAmount} 是**本店商品的小计**，不等于订单的 {@code total_amount}
 * ——后者含其他商家的商品与整单优惠。{@code items} 同样只含本店明细。</p>
 */
@Data
@Accessors(chain = true)
public class MerchantOrderVO {

    private Long orderId;
    private String orderNo;
    private String status;
    private BigDecimal storeAmount;
    private LocalDateTime createdAt;

    private String receiver;
    private String phone;
    private String address;

    private List<MerchantOrderItemVO> items;

}
