package com.mall.module.order.entity.vo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

/**
 * 售后资格查询结果。
 *
 * <p>{@code policyCode} 是判定与解释之间的接缝：代码给出政策码，agent 据此
 * 检索对应条款来组织解释。</p>
 */
@Data
@Accessors(chain = true)
public class RefundEligibilityVO {

    private Long orderId;
    /** 当前是否可退。 */
    private boolean eligible;
    /** 不可退时的原因，可直接展示给用户。 */
    private String reason;
    /** 适用政策码，不可退时为 null。 */
    private String policyCode;
    private String policyTitle;
    /** 可退金额，由服务端从订单算出。 */
    private BigDecimal refundableAmount;
    /** 是否已有退款记录（含已完成），用于避免重复申请。 */
    private boolean refundExists;
}
