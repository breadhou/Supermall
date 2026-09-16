package com.mall.module.logistics.controller;

import com.mall.common.enums.ResultStatus;
import com.mall.common.result.Result;
import com.mall.module.logistics.entity.vo.LogisticsVO;
import com.mall.module.logistics.service.LogisticsService;
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
class LogisticsControllerTest {

    @Mock
    private LogisticsService logisticsService;

    @InjectMocks
    private LogisticsController logisticsController;

    @Test
    void getLogistics_shouldDelegateToService() {
        LogisticsVO logistics = new LogisticsVO();
        logistics.setOrderId(9001L);
        logistics.setStatus("SHIPPED");
        when(logisticsService.getLogistics(9001L)).thenReturn(logistics);

        Result<LogisticsVO> result = logisticsController.getLogistics(9001L);

        assertNotNull(result);
        assertEquals(ResultStatus.SUCCESS, result.getStatus());
        assertEquals(9001L, result.getData().getOrderId());
        verify(logisticsService).getLogistics(9001L);
    }

}
