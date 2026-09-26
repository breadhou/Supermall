package com.mall.module.order.controller;

import com.mall.common.result.Result;
import com.mall.module.order.entity.dto.RefundReasonDTO;
import com.mall.module.order.entity.vo.RefundEligibilityVO;
import com.mall.module.order.service.RefundEligibilityService;
import com.mall.module.order.service.RefundExecutionService;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    void reasonOnlyHttpRemainsValid() throws Exception {
        when(executionService.execute(ORDER_ID, "不想要了")).thenReturn(
                new RefundEligibilityVO().setOrderId(ORDER_ID).setEligible(true));

        MockMvc mockMvc = mockMvc();
        mockMvc.perform(post("/api/orders/{id}/refund/execute", ORDER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"不想要了\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        assertEquals("不想要了", new RefundReasonDTO("不想要了").getReason());
        verify(executionService).execute(ORDER_ID, "不想要了");
    }

    @Test
    void rejectsPartialReviewExpectationPairWithParameterError() throws Exception {
        MockMvc mockMvc = mockMvc();

        mockMvc.perform(post("/api/orders/{id}/refund/execute", ORDER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"不想要了\","
                                + "\"expectedCatalogFingerprint\":\"reviewed-fingerprint\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(10000));

        verifyNoInteractions(executionService);
    }

    @Test
    void rejectsBlankReviewExpectationPairMemberWithParameterError() throws Exception {
        MockMvc mockMvc = mockMvc();

        mockMvc.perform(post("/api/orders/{id}/refund/execute", ORDER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"不想要了\","
                                + "\"expectedCatalogFingerprint\":\" \","
                                + "\"expectedPolicyCode\":\"SEVEN_DAY_NO_REASON\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(10000));

        verifyNoInteractions(executionService);
    }

    private MockMvc mockMvc() {
        ObjectMapper objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }
}
