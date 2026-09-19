package com.mall.module.order.service;

import com.mall.module.order.entity.vo.RefundEligibilityVO;

public interface RefundExecutionService {

    /**
     * 执行退款。幂等：重复调用返回首次结果，不报错也不重复退款。
     *
     * @param reason 用户给出的退款原因，仅作记录，不影响金额
     */
    RefundEligibilityVO execute(Long orderId, String reason);
}
