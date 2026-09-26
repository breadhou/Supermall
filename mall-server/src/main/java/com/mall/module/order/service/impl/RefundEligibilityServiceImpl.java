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
import com.mall.module.order.service.AfterSalesPolicyCatalog;
import com.mall.module.order.service.RefundEligibilityService;
import com.mall.module.order.service.RefundEligibilityEvaluator;
import com.mall.module.order.service.RefundEligibilityEvaluator.Assessment;
import com.mall.security.utils.UserContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class RefundEligibilityServiceImpl implements RefundEligibilityService {

    private final OrderMapper orderMapper;
    private final RefundMapper refundMapper;
    private final AfterSalesPolicyCatalog policyCatalog;
    private final RefundEligibilityEvaluator evaluator;

    @Autowired
    public RefundEligibilityServiceImpl(OrderMapper orderMapper, RefundMapper refundMapper,
                                        AfterSalesPolicyCatalog policyCatalog,
                                        RefundEligibilityEvaluator evaluator) {
        this.orderMapper = orderMapper;
        this.refundMapper = refundMapper;
        this.policyCatalog = policyCatalog;
        this.evaluator = evaluator;
    }

    public RefundEligibilityServiceImpl(OrderMapper orderMapper, RefundMapper refundMapper,
                                        AfterSalesPolicyCatalog policyCatalog) {
        this(orderMapper, refundMapper, policyCatalog, new RefundEligibilityEvaluator());
    }

    @Override
    public RefundEligibilityVO check(Long orderId) {
        Long userId = UserContext.getUserId();
        Order order = orderMapper.selectById(orderId);
        if (order == null || !userId.equals(order.getUserId())) {
            throw new BusinessException(ResultStatus.ORDER_NOT_EXIST);
        }

        Assessment assessment = evaluator.assess(order);
        RefundEligibilityVO vo = new RefundEligibilityVO()
                .setOrderId(orderId)
                .setRefundableAmount(assessment.refundableAmount())
                .setOrderStatus(assessment.orderStatus())
                .setCatalogFingerprint(policyCatalog.currentSnapshot().getFingerprint());

        Refund existing = refundMapper.selectOne(
                new LambdaQueryWrapper<Refund>().eq(Refund::getOrderId, orderId));
        vo.setRefundExists(existing != null);
        if (existing != null) {
            return vo.setEligible(false).setReason("该订单已有退款记录，不能重复申请");
        }

        AfterSalesPolicy policy = assessment.policy();
        if (policy == null) {
            return vo.setEligible(false)
                    .setReason("订单当前状态（" + assessment.orderStatus() + "）不符合任何售后政策");
        }

        return vo.setEligible(true)
                .setPolicyCode(policy.name())
                .setPolicyTitle(policy.getTitle());
    }

}
