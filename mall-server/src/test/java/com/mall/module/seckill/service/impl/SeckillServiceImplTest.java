package com.mall.module.seckill.service.impl;

import com.mall.common.enums.ResultStatus;
import com.mall.common.exception.BusinessException;
import com.mall.infra.redis.RedisLock;
import com.mall.infra.redis.RedisService;
import com.mall.infra.redis.SeckillKey;
import com.mall.module.seckill.entity.po.SeckillActivity;
import com.mall.module.seckill.entity.po.SeckillItem;
import com.mall.module.seckill.entity.po.SeckillOrder;
import com.mall.module.seckill.entity.vo.SeckillResultVO;
import com.mall.module.seckill.mapper.SeckillActivityMapper;
import com.mall.module.seckill.mapper.SeckillItemMapper;
import com.mall.module.seckill.mapper.SeckillOrderMapper;
import com.mall.module.seckill.mq.SeckillMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import com.mall.security.utils.UserContext;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SeckillServiceImplTest {

    @Mock
    private SeckillActivityMapper activityMapper;

    @Mock
    private SeckillItemMapper itemMapper;

    @Mock
    private SeckillOrderMapper orderMapper;

    @Mock
    private RedisService redisService;

    @Mock
    private RedisLock redisLock;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private SeckillServiceImpl seckillService;

    private MockedStatic<UserContext> userContextMock;

    private static final Long USER_ID = 1001L;
    private static final Long ITEM_ID = 2001L;
    private static final Long ACTIVITY_ID = 3001L;
    private static final String PATH = "test-seckill-path";
    private static final String USER_ITEM_KEY = ITEM_ID + ":" + USER_ID;

    private SeckillItem seckillItem;
    private SeckillActivity activity;

    @BeforeEach
    void setUp() {
        userContextMock = mockStatic(UserContext.class);
        userContextMock.when(UserContext::getUserId).thenReturn(USER_ID);

        seckillItem = new SeckillItem()
                .setId(ITEM_ID)
                .setActivityId(ACTIVITY_ID)
                .setSkuId(4001L)
                .setSeckillPrice(new BigDecimal("99.00"))
                .setStock(10)
                .setLimitPerUser(1);

        activity = new SeckillActivity()
                .setId(ACTIVITY_ID)
                .setStartTime(LocalDateTime.now().minusMinutes(1))
                .setEndTime(LocalDateTime.now().plusMinutes(10))
                .setStatus("IN_PROGRESS");
    }

    @AfterEach
    void tearDown() {
        if (userContextMock != null) {
            userContextMock.close();
        }
    }

    @Test
    void preheatStock_shouldWriteStockToRedis() {
        when(itemMapper.selectById(ITEM_ID)).thenReturn(seckillItem);

        seckillService.preheatStock(ITEM_ID);

        verify(redisService).set(SeckillKey.stock, ITEM_ID.toString(), 10);
    }

    @Test
    void preheatStock_shouldThrowException_whenItemNotFound() {
        when(itemMapper.selectById(ITEM_ID)).thenReturn(null);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> seckillService.preheatStock(ITEM_ID)
        );

        assertEquals(ResultStatus.DATA_NOT_FOUND, exception.getStatus());
        verify(redisService, never()).set(any(), anyString(), any());
    }

    @Test
    void getPath_shouldReturnPath_whenActivityIsInProgress() {
        when(itemMapper.selectById(ITEM_ID)).thenReturn(seckillItem);
        when(activityMapper.selectById(ACTIVITY_ID)).thenReturn(activity);

        String result = seckillService.getPath(ITEM_ID);

        assertNotNull(result);
        assertFalse(result.isBlank());
        verify(redisService).set(eq(SeckillKey.path), eq(USER_ITEM_KEY), anyString());
    }

    @Test
    void getPath_shouldThrowException_whenActivityHasEnded() {
        activity.setStartTime(LocalDateTime.now().minusMinutes(10));
        activity.setEndTime(LocalDateTime.now().minusMinutes(1));
        when(itemMapper.selectById(ITEM_ID)).thenReturn(seckillItem);
        when(activityMapper.selectById(ACTIVITY_ID)).thenReturn(activity);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> seckillService.getPath(ITEM_ID)
        );

        assertEquals(ResultStatus.SECKILL_END, exception.getStatus());
        verify(redisService, never()).set(eq(SeckillKey.path), anyString(), any());
    }

    @Test
    void getCountdown_shouldReturnActivityAndStockInfo() {
        when(itemMapper.selectById(ITEM_ID)).thenReturn(seckillItem);
        when(activityMapper.selectById(ACTIVITY_ID)).thenReturn(activity);
        when(redisService.get(SeckillKey.stock, ITEM_ID.toString(), Integer.class)).thenReturn(7);

        var result = seckillService.getCountdown(ITEM_ID);

        assertEquals("IN_PROGRESS", result.getActivityStatus());
        assertEquals(new BigDecimal("99.00"), result.getSeckillPrice());
        assertEquals(7, result.getRemainingStock());
        assertEquals(1, result.getLimitPerUser());
    }

    @Test
    void executeSeckill_shouldQueueMessage_whenLuaDeductsStock() {
        when(itemMapper.selectById(ITEM_ID)).thenReturn(seckillItem);
        when(redisService.get(SeckillKey.path, USER_ITEM_KEY, String.class)).thenReturn(PATH);
        when(redisService.get(SeckillKey.result, USER_ITEM_KEY, Integer.class)).thenReturn(null);
        when(redisService.get(SeckillKey.userLimit, USER_ITEM_KEY, Integer.class)).thenReturn(null);
        when(redisLock.lock(anyString(), eq(10L), any())).thenReturn("lock-value");
        doReturn(1L).when(redisTemplate).execute(any(RedisScript.class), anyList(), any());

        SeckillResultVO result = seckillService.executeSeckill(ITEM_ID, PATH);

        assertEquals("WAITING", result.getStatus());
        verify(redisService).set(SeckillKey.result, USER_ITEM_KEY, 0);

        ArgumentCaptor<SeckillMessage> messageCaptor = ArgumentCaptor.forClass(SeckillMessage.class);
        verify(rabbitTemplate).convertAndSend(
                eq("mall.seckill.direct"),
                eq("order.create"),
                messageCaptor.capture()
        );
        assertEquals(USER_ID, messageCaptor.getValue().getUserId());
        assertEquals(ITEM_ID, messageCaptor.getValue().getSeckillItemId());
        assertEquals(1, messageCaptor.getValue().getQuantity());
        verify(redisLock).unlock(anyString(), eq("lock-value"));
    }

    @Test
    void executeSeckill_shouldThrowRepeat_whenResultIsWaiting() {
        when(itemMapper.selectById(ITEM_ID)).thenReturn(seckillItem);
        when(redisService.get(SeckillKey.path, USER_ITEM_KEY, String.class)).thenReturn(PATH);
        when(redisService.get(SeckillKey.result, USER_ITEM_KEY, Integer.class)).thenReturn(0);
        when(redisLock.lock(anyString(), eq(10L), any())).thenReturn("lock-value");

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> seckillService.executeSeckill(ITEM_ID, PATH)
        );

        assertEquals(ResultStatus.SECKILL_REPEAT, exception.getStatus());
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any());
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(SeckillMessage.class));
    }

    @Test
    void executeSeckill_shouldThrowException_whenPathIsInvalid() {
        when(itemMapper.selectById(ITEM_ID)).thenReturn(seckillItem);
        when(redisService.get(SeckillKey.path, USER_ITEM_KEY, String.class)).thenReturn(PATH);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> seckillService.executeSeckill(ITEM_ID, "wrong-path")
        );

        assertEquals(ResultStatus.SECKILL_FAIL, exception.getStatus());
        verify(redisLock, never()).lock(anyString(), anyLong(), any());
    }

    @Test
    void executeSeckill_shouldThrowEnd_whenLuaReportsInsufficientStock() {
        when(itemMapper.selectById(ITEM_ID)).thenReturn(seckillItem);
        when(redisService.get(SeckillKey.path, USER_ITEM_KEY, String.class)).thenReturn(PATH);
        when(redisService.get(SeckillKey.result, USER_ITEM_KEY, Integer.class)).thenReturn(null);
        when(redisService.get(SeckillKey.userLimit, USER_ITEM_KEY, Integer.class)).thenReturn(null);
        when(redisLock.lock(anyString(), eq(10L), any())).thenReturn("lock-value");
        doReturn(0L).when(redisTemplate).execute(any(RedisScript.class), anyList(), any());

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> seckillService.executeSeckill(ITEM_ID, PATH)
        );

        assertEquals(ResultStatus.SECKILL_END, exception.getStatus());
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(SeckillMessage.class));
    }

    @Test
    void executeSeckill_shouldRollbackStock_whenMessageSendFails() {
        String stockKey = SeckillKey.stock.getPrefix() + ITEM_ID;
        when(itemMapper.selectById(ITEM_ID)).thenReturn(seckillItem);
        when(redisService.get(SeckillKey.path, USER_ITEM_KEY, String.class)).thenReturn(PATH);
        when(redisService.get(SeckillKey.result, USER_ITEM_KEY, Integer.class)).thenReturn(null);
        when(redisService.get(SeckillKey.userLimit, USER_ITEM_KEY, Integer.class)).thenReturn(null);
        when(redisLock.lock(anyString(), eq(10L), any())).thenReturn("lock-value");
        doReturn(1L).when(redisTemplate).execute(any(RedisScript.class), anyList(), any());
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        doThrow(new RuntimeException("RabbitMQ unavailable"))
                .when(rabbitTemplate)
                .convertAndSend(anyString(), anyString(), any(SeckillMessage.class));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> seckillService.executeSeckill(ITEM_ID, PATH)
        );

        assertEquals(ResultStatus.SECKILL_FAIL, exception.getStatus());
        verify(valueOperations).increment(stockKey);
        verify(redisService).set(SeckillKey.result, USER_ITEM_KEY, -1);
    }

    @Test
    void pollResult_shouldReturnWaiting_whenResultIsZero() {
        when(redisService.get(SeckillKey.result, USER_ITEM_KEY, Integer.class)).thenReturn(0);

        SeckillResultVO result = seckillService.pollResult(ITEM_ID);

        assertEquals("WAITING", result.getStatus());
        assertNull(result.getOrderId());
    }

    @Test
    void pollResult_shouldReturnSuccessWithOrderId_whenResultIsOne() {
        when(redisService.get(SeckillKey.result, USER_ITEM_KEY, Integer.class)).thenReturn(1);
        when(orderMapper.selectOne(any())).thenReturn(
                new SeckillOrder().setOrderId(9001L)
        );

        SeckillResultVO result = seckillService.pollResult(ITEM_ID);

        assertEquals("SUCCESS", result.getStatus());
        assertEquals(9001L, result.getOrderId());
    }

    @Test
    void pollResult_shouldReturnFailed_whenResultIsMinusOne() {
        when(redisService.get(SeckillKey.result, USER_ITEM_KEY, Integer.class)).thenReturn(-1);

        SeckillResultVO result = seckillService.pollResult(ITEM_ID);

        assertEquals("FAILED", result.getStatus());
        assertEquals("库存不足或订单处理失败", result.getReason());
    }

    @Test
    void pollResult_shouldReturnNotFound_whenNoResultExists() {
        when(redisService.get(SeckillKey.result, USER_ITEM_KEY, Integer.class)).thenReturn(null);

        SeckillResultVO result = seckillService.pollResult(ITEM_ID);

        assertEquals("NOT_FOUND", result.getStatus());
        assertEquals("未参与秒杀", result.getReason());
    }
}
