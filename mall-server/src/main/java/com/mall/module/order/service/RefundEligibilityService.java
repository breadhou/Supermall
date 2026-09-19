package com.mall.module.order.service;

import com.mall.module.order.entity.vo.RefundEligibilityVO;

public interface RefundEligibilityService {

    /** 只读：判断订单的售后资格与可退金额，无任何副作用。 */
    RefundEligibilityVO check(Long orderId);
}
