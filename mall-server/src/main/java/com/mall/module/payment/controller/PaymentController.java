package com.mall.module.payment.controller;

import com.mall.common.result.Result;
import com.mall.module.payment.entity.vo.PaymentVO;
import com.mall.module.payment.service.PaymentService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/{orderId}/pay")
    public Result<PaymentVO> pay(@PathVariable Long orderId) {
        Result<PaymentVO> result = Result.build();
        result.success(paymentService.pay(orderId));
        return result;
    }

    @GetMapping("/{orderId}/payment")
    public Result<PaymentVO> getPayment(@PathVariable Long orderId) {
        Result<PaymentVO> result = Result.build();
        result.success(paymentService.getPayment(orderId));
        return result;
    }

}
