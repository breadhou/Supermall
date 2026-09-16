package com.mall.module.payment.service.impl;

import com.mall.common.enums.ResultStatus;
import com.mall.common.exception.BusinessException;
import com.mall.common.utils.SnowflakeIdUtil;
import com.mall.module.order.entity.po.Order;
import com.mall.module.order.mapper.OrderMapper;
import com.mall.module.payment.entity.po.PaymentRecord;
import com.mall.module.payment.entity.vo.PaymentVO;
import com.mall.module.payment.mapper.PaymentRecordMapper;
import com.mall.module.payment.service.PaymentService;
import com.mall.security.utils.UserContext;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class PaymentServiceImpl implements PaymentService {

    private static final String SIMULATED = "SIMULATED";
    private static final String PENDING = "PENDING";
    private static final String SUCCESS = "SUCCESS";
    private static final String PAID = "PAID";

    private final PaymentRecordMapper paymentRecordMapper;
    private final OrderMapper orderMapper;

    public PaymentServiceImpl(PaymentRecordMapper paymentRecordMapper, OrderMapper orderMapper) {
        this.paymentRecordMapper = paymentRecordMapper;
        this.orderMapper = orderMapper;
    }

    @Override
    @Transactional
    public PaymentVO pay(Long orderId) {
        Long userId = currentUserId();
        Order order = orderMapper.selectByIdForUpdate(orderId);
        verifyOwnership(order, userId);

        // The order row is locked before reading the payment record.  A retry
        // that races with the first request therefore sees the committed
        // SUCCESS record instead of creating a second payment.
        PaymentRecord payment = paymentRecordMapper.selectByOrderIdForUpdate(orderId);
        if (payment != null && SUCCESS.equals(payment.getStatus())) {
            if (PENDING.equals(order.getStatus())) {
                order.setStatus(PAID);
                orderMapper.updateById(order);
            }
            return toVO(payment);
        }

        if (!PENDING.equals(order.getStatus())) {
            throw new BusinessException(ResultStatus.PAYMENT_STATUS_ERROR);
        }

        LocalDateTime paidAt = LocalDateTime.now();
        if (payment == null) {
            payment = new PaymentRecord()
                    .setId(SnowflakeIdUtil.nextId())
                    .setOrderId(orderId)
                    .setAmount(order.getTotalAmount())
                    .setMethod(SIMULATED)
                    .setStatus(SUCCESS)
                    .setPaidAt(paidAt)
                    .setCreatedAt(paidAt);
            paymentRecordMapper.insert(payment);
        } else {
            payment.setAmount(order.getTotalAmount())
                    .setMethod(SIMULATED)
                    .setStatus(SUCCESS)
                    .setPaidAt(paidAt);
            paymentRecordMapper.updateById(payment);
        }

        order.setStatus(PAID);
        orderMapper.updateById(order);
        return toVO(payment);
    }

    @Override
    public PaymentVO getPayment(Long orderId) {
        Long userId = currentUserId();
        Order order = orderMapper.selectById(orderId);
        verifyOwnership(order, userId);

        PaymentRecord payment = paymentRecordMapper.selectByOrderId(orderId);
        if (payment == null) {
            throw new BusinessException(ResultStatus.PAYMENT_NOT_EXIST);
        }
        return toVO(payment);
    }

    private Long currentUserId() {
        return UserContext.getUserId();
    }

    private void verifyOwnership(Order order, Long userId) {
        if (order == null || userId == null || !userId.equals(order.getUserId())) {
            throw new BusinessException(ResultStatus.ORDER_NOT_EXIST);
        }
    }

    private PaymentVO toVO(PaymentRecord payment) {
        PaymentVO vo = new PaymentVO();
        BeanUtils.copyProperties(payment, vo);
        return vo;
    }

}
