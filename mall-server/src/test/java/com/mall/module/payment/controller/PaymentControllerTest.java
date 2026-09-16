package com.mall.module.payment.controller;

import com.mall.common.enums.ResultStatus;
import com.mall.common.result.Result;
import com.mall.module.payment.entity.vo.PaymentVO;
import com.mall.module.payment.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentControllerTest {

    @Mock
    private PaymentService paymentService;

    @InjectMocks
    private PaymentController paymentController;

    @Test
    void pay_shouldDelegateToService() {
        PaymentVO payment = new PaymentVO();
        payment.setOrderId(9001L);
        payment.setStatus("SUCCESS");
        when(paymentService.pay(9001L)).thenReturn(payment);

        Result<PaymentVO> result = paymentController.pay(9001L);

        assertSuccess(result);
        assertEquals(9001L, result.getData().getOrderId());
        verify(paymentService).pay(9001L);
    }

    @Test
    void getPayment_shouldDelegateToService() {
        PaymentVO payment = new PaymentVO();
        payment.setOrderId(9001L);
        payment.setStatus("SUCCESS");
        when(paymentService.getPayment(9001L)).thenReturn(payment);

        Result<PaymentVO> result = paymentController.getPayment(9001L);

        assertSuccess(result);
        assertEquals("SUCCESS", result.getData().getStatus());
        verify(paymentService).getPayment(9001L);
    }

    private void assertSuccess(Result<?> result) {
        assertNotNull(result);
        assertEquals(ResultStatus.SUCCESS, result.getStatus());
        assertEquals(0, result.getCode());
    }

}
