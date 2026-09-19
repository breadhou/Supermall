package com.mall.module.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.common.enums.ResultStatus;
import com.mall.common.exception.BusinessException;
import com.mall.module.order.entity.po.Order;
import com.mall.module.order.entity.po.Refund;
import com.mall.module.order.entity.vo.RefundEligibilityVO;
import com.mall.module.order.enums.AfterSalesPolicy;
import com.mall.module.order.mapper.OrderMapper;
import com.mall.module.order.mapper.RefundMapper;
import com.mall.module.order.service.RefundEligibilityService;
import com.mall.security.utils.UserContext;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;

@Service
public class RefundEligibilityServiceImpl implements RefundEligibilityService {

    private final OrderMapper orderMapper;
    private final RefundMapper refundMapper;

    public RefundEligibilityServiceImpl(OrderMapper orderMapper, RefundMapper refundMapper) {
        this.orderMapper = orderMapper;
        this.refundMapper = refundMapper;
    }

    @Override
    public RefundEligibilityVO check(Long orderId) {
        Long userId = UserContext.getUserId();
        Order order = orderMapper.selectById(orderId);
        if (order == null || !userId.equals(order.getUserId())) {
            throw new BusinessException(ResultStatus.ORDER_NOT_EXIST);
        }

        RefundEligibilityVO vo = new RefundEligibilityVO()
                .setOrderId(orderId)
                .setRefundableAmount(order.getTotalAmount());

        Refund existing = refundMapper.selectOne(
                new LambdaQueryWrapper<Refund>().eq(Refund::getOrderId, orderId));
        vo.setRefundExists(existing != null);
        if (existing != null) {
            return vo.setEligible(false).setReason("该订单已有退款记录，不能重复申请");
        }

        long daysSinceReceipt = daysSince(order.getCreatedAt());
        AfterSalesPolicy policy = AfterSalesPolicy.resolve(order.getStatus(), daysSinceReceipt);
        if (policy == null) {
            return vo.setEligible(false)
                    .setReason("订单当前状态（" + order.getStatus() + "）不符合任何售后政策");
        }

        return vo.setEligible(true)
                .setPolicyCode(policy.name())
                .setPolicyTitle(policy.getTitle());
    }

    /**
     * 距签收天数。当前订单表没有签收时间字段，用创建时间近似——
     * 这是本阶段的已知简化，签收时间落地后应替换。
     */
    private long daysSince(LocalDateTime from) {
        if (from == null) {
            return 0;
        }
        return Duration.between(from, LocalDateTime.now()).toDays();
    }
}
