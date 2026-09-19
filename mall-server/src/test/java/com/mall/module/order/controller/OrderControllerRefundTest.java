package com.mall.module.order.controller;

import com.mall.common.result.Result;
import com.mall.module.order.entity.dto.RefundReasonDTO;
import com.mall.module.order.entity.vo.RefundEligibilityVO;
import com.mall.module.order.service.RefundEligibilityService;
import com.mall.module.order.service.RefundExecutionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderControllerRefundTest {

    private static final Long ORDER_ID = 9001L;

    @Mock
    private RefundEligibilityService eligibilityService;
    @Mock
    private RefundExecutionService executionService;

    @InjectMocks
    private OrderController controller;

    @Test
    void eligibilityEndpoint_shouldReturnServiceResult() {
        when(eligibilityService.check(ORDER_ID)).thenReturn(new RefundEligibilityVO()
                .setOrderId(ORDER_ID).setEligible(true)
                .setPolicyCode("SEVEN_DAY_NO_REASON")
                .setRefundableAmount(new BigDecimal("199.99")));

        Result<RefundEligibilityVO> result = controller.refundEligibility(ORDER_ID);

        assertEquals(0, result.getCode());
        assertEquals("SEVEN_DAY_NO_REASON", result.getData().getPolicyCode());
    }

    @Test
    void executeEndpoint_shouldDelegateWithReasonOnly() {
        when(executionService.execute(ORDER_ID, "不想要了")).thenReturn(
                new RefundEligibilityVO().setOrderId(ORDER_ID).setEligible(true));

        controller.executeRefund(ORDER_ID, new RefundReasonDTO("不想要了"));

        // 端点只透传原因，金额由服务端决定
        verify(executionService).execute(ORDER_ID, "不想要了");
    }
}
