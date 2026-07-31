package com.mall.module.seckill.controller;

import com.mall.common.enums.ResultStatus;
import com.mall.common.result.Result;
import com.mall.module.seckill.entity.vo.SeckillCountdownVO;
import com.mall.module.seckill.entity.vo.SeckillResultVO;
import com.mall.module.seckill.service.SeckillService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SeckillControllerTest {

    @Mock
    private SeckillService seckillService;

    @InjectMocks
    private SeckillController seckillController;

    private static final Long ITEM_ID = 2001L;
    private static final String PATH = "test-path";

    @Test
    void preheatStock_shouldReturnSuccessResult() {
        Result<Void> result = seckillController.preheatStock(ITEM_ID);

        assertSuccess(result);
        verify(seckillService).preheatStock(ITEM_ID);
    }

    @Test
    void getPath_shouldReturnPath() {
        when(seckillService.getPath(ITEM_ID)).thenReturn(PATH);

        Result<String> result = seckillController.getPath(ITEM_ID);

        assertSuccess(result);
        assertEquals(PATH, result.getData());
    }

    @Test
    void getCountdown_shouldReturnCountdownVO() {
        SeckillCountdownVO vo = new SeckillCountdownVO();
        vo.setActivityStatus("IN_PROGRESS");
        vo.setSeckillPrice(new BigDecimal("99.00"));
        when(seckillService.getCountdown(ITEM_ID)).thenReturn(vo);

        Result<SeckillCountdownVO> result = seckillController.getCountdown(ITEM_ID);

        assertSuccess(result);
        assertEquals("IN_PROGRESS", result.getData().getActivityStatus());
        assertEquals(new BigDecimal("99.00"), result.getData().getSeckillPrice());
    }

    @Test
    void executeSeckill_shouldReturnResultVO() {
        SeckillResultVO vo = new SeckillResultVO();
        vo.setStatus("WAITING");
        when(seckillService.executeSeckill(ITEM_ID, PATH)).thenReturn(vo);

        Result<SeckillResultVO> result = seckillController.executeSeckill(ITEM_ID, PATH);

        assertSuccess(result);
        assertEquals("WAITING", result.getData().getStatus());
        verify(seckillService).executeSeckill(ITEM_ID, PATH);
    }

    @Test
    void pollResult_shouldReturnResultVO() {
        SeckillResultVO vo = new SeckillResultVO();
        vo.setStatus("SUCCESS");
        vo.setOrderId(9001L);
        when(seckillService.pollResult(ITEM_ID)).thenReturn(vo);

        Result<SeckillResultVO> result = seckillController.pollResult(ITEM_ID);

        assertSuccess(result);
        assertEquals("SUCCESS", result.getData().getStatus());
        assertEquals(9001L, result.getData().getOrderId());
    }

    private void assertSuccess(Result<?> result) {
        assertNotNull(result);
        assertEquals(ResultStatus.SUCCESS, result.getStatus());
        assertEquals(0, result.getCode());
    }
}
