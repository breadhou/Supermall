package com.mall.module.payment.service;

import com.mall.module.payment.entity.vo.PaymentVO;

public interface PaymentService {

    /** Complete the local simulated payment for the current user's order. */
    PaymentVO pay(Long orderId);

    /** Query the payment record for the current user's order. */
    PaymentVO getPayment(Long orderId);

}
