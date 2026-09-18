package com.mall.module.admin.entity.vo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

/**
 * 平台概览。
 *
 * <p>{@code gmv} 的口径：**已支付及之后**的订单金额之和，排除 {@code PENDING}
 * （尚未付款）与 {@code CANCELLED}（已取消）。不是所有订单金额的简单相加。</p>
 */
@Data
@Accessors(chain = true)
public class AdminStatisticsVO {

    private Long userCount;
    private Long merchantCount;
    private Long productCount;
    private Long orderCount;
    private Long paidOrderCount;
    private BigDecimal gmv;

}
