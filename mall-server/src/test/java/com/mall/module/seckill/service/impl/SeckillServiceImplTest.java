package com.mall.module.seckill.service.impl;

import com.mall.common.enums.ResultStatus;
import com.mall.common.exception.BusinessException;
import com.mall.infra.redis.RedisService;
import com.mall.infra.redis.SeckillKey;
import com.mall.module.seckill.entity.po.SeckillActivity;
import com.mall.module.seckill.entity.po.SeckillItem;
import com.mall.module.seckill.entity.po.SeckillOrder;
import com.mall.module.seckill.entity.vo.SeckillRequestSnapshot;
import com.mall.module.seckill.entity.vo.SeckillResultVO;
import com.mall.module.seckill.mapper.SeckillActivityMapper;
import com.mall.module.seckill.mapper.SeckillItemMapper;
import com.mall.module.seckill.mapper.SeckillOrderMapper;
import com.mall.module.seckill.mq.SeckillMessage;
import com.mall.module.seckill.mq.SeckillMessagePublisher;
import com.mall.module.seckill.redis.SeckillRedisStateService;
import com.mall.module.user.entity.po.Address;
import com.mall.module.user.mapper.AddressMapper;
import com.mall.security.utils.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeckillServiceImplTest {

    @Mock
    private SeckillActivityMapper activityMapper;

    @Mock
    private SeckillItemMapper itemMapper;

    @Mock
    private SeckillOrderMapper orderMapper;

    @Mock
    private AddressMapper addressMapper;

    @Mock
    private RedisService redisService;

    @Mock
    private SeckillRedisStateService redisStateService;

    @Mock
    private SeckillMessagePublisher messagePublisher;

    @InjectMocks
    private SeckillServiceImpl seckillService;

    private MockedStatic<UserContext> userContextMock;

    private static final Long USER_ID = 1001L;
    private static final Long ITEM_ID = 2001L;
    private static final Long ACTIVITY_ID = 3001L;
    private static final Long ADDRESS_ID = 5001L;
    private static final String PATH = "test-seckill-path";

    private SeckillItem seckillItem;
    private SeckillActivity activity;
    private SeckillRequestSnapshot requestSnapshot;

    @BeforeEach
    void setUp() {
        userContextMock = org.mockito.Mockito.mockStatic(UserContext.class);
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

        requestSnapshot = new SeckillRequestSnapshot()
                .setPath(PATH)
                .setSkuId(4001L)
                .setSeckillPrice(new BigDecimal("99.00"))
                .setLimitPerUser(1)
                .setAddressId(ADDRESS_ID);
    }

    @AfterEach
    void tearDown() {
        if (userContextMock != null) {
            userContextMock.close();
        }
    }

    @Test
    void preheatStock_shouldWriteClusterSafeStockAndSnapshot() {
        when(itemMapper.selectById(ITEM_ID)).thenReturn(seckillItem);

        seckillService.preheatStock(ITEM_ID);

        verify(redisService).setValue(SeckillKey.stockKey(ITEM_ID), 10);
        verify(redisService).setValue(eq(SeckillKey.itemSnapshotKey(ITEM_ID)), any());
        verify(redisStateService).registerItem(ITEM_ID);
    }

    @Test
    void getPath_shouldCachePathAndAddressSnapshot() {
        when(itemMapper.selectById(ITEM_ID)).thenReturn(seckillItem);
        when(activityMapper.selectById(ACTIVITY_ID)).thenReturn(activity);
        when(addressMapper.selectOne(any())).thenReturn(new Address().setId(ADDRESS_ID));

        String result = seckillService.getPath(ITEM_ID);

        assertNotNull(result);
        assertFalse(result.isBlank());
        verify(redisService).set(
                eq(SeckillKey.pathKey(ITEM_ID, USER_ID)),
                eq(result),
                eq(60L),
                eq(TimeUnit.SECONDS)
        );
        verify(redisService).setValue(
                eq(SeckillKey.requestSnapshotKey(ITEM_ID, USER_ID)),
                any(),
                eq(60L),
                eq(TimeUnit.SECONDS)
        );
    }

    @Test
    void getPath_shouldThrowWhenActivityHasEnded() {
        activity.setEndTime(LocalDateTime.now().minusMinutes(1));
        when(itemMapper.selectById(ITEM_ID)).thenReturn(seckillItem);
        when(activityMapper.selectById(ACTIVITY_ID)).thenReturn(activity);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> seckillService.getPath(ITEM_ID)
        );

        assertEquals(ResultStatus.SECKILL_END, exception.getStatus());
        verify(addressMapper, never()).selectOne(any());
    }

    @Test
    void getCountdown_shouldReadClusterSafeStock() {
        when(itemMapper.selectById(ITEM_ID)).thenReturn(seckillItem);
        when(activityMapper.selectById(ACTIVITY_ID)).thenReturn(activity);
        when(redisService.getValue(SeckillKey.stockKey(ITEM_ID), Integer.class)).thenReturn(7);

        var result = seckillService.getCountdown(ITEM_ID);

        assertEquals("IN_PROGRESS", result.getActivityStatus());
        assertEquals(new BigDecimal("99.00"), result.getSeckillPrice());
        assertEquals(7, result.getRemainingStock());
    }

    @Test
    void executeSeckill_shouldNotReadItemFromDatabaseAndShouldWaitAfterConfirm() {
        stubReservation(1L);

        SeckillResultVO result = seckillService.executeSeckill(ITEM_ID, PATH);

        assertEquals("WAITING", result.getStatus());
        verify(itemMapper, never()).selectById(ITEM_ID);
        ArgumentCaptor<SeckillMessage> captor = ArgumentCaptor.forClass(SeckillMessage.class);
        verify(messagePublisher).publishAsync(captor.capture());
        assertEquals(USER_ID, captor.getValue().getUserId());
        assertEquals(4001L, captor.getValue().getSkuId());
        assertEquals(ADDRESS_ID, captor.getValue().getAddressId());
    }

    @Test
    void executeSeckill_shouldReturnRepeatFromLua() {
        stubSnapshot();
        when(redisStateService.reserve(
                eq(ITEM_ID), eq(USER_ID), eq(PATH), eq(1), eq(1), anyString(), eq(3600L)
        )).thenReturn(-4L);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> seckillService.executeSeckill(ITEM_ID, PATH)
        );

        assertEquals(ResultStatus.SECKILL_REPEAT, exception.getStatus());
        verify(messagePublisher, never()).publishAsync(any());
    }

    @Test
    void executeSeckill_shouldReturnEndWhenLuaReportsEmptyStock() {
        stubSnapshot();
        when(redisStateService.reserve(
                eq(ITEM_ID), eq(USER_ID), eq(PATH), eq(1), eq(1), anyString(), eq(3600L)
        )).thenReturn(0L);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> seckillService.executeSeckill(ITEM_ID, PATH)
        );

        assertEquals(ResultStatus.SECKILL_END, exception.getStatus());
    }

    @Test
    void executeSeckill_shouldRollbackWhenPublisherIsNacked() {
        stubReservation(1L);
        when(messagePublisher.publishAsync(any())).thenReturn(
                CompletableFuture.completedFuture(new SeckillMessagePublisher.PublishResult(false, "nack"))
        );

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> seckillService.executeSeckill(ITEM_ID, PATH)
        );

        assertEquals(ResultStatus.SECKILL_FAIL, exception.getStatus());
        verify(redisStateService).rollback(any(SeckillMessage.class), eq(false), anyLong());
    }

    @Test
    void executeSeckill_shouldFailWhenSnapshotIsMissing() {
        when(redisService.getValue(
                SeckillKey.requestSnapshotKey(ITEM_ID, USER_ID),
                SeckillRequestSnapshot.class
        )).thenReturn(null);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> seckillService.executeSeckill(ITEM_ID, PATH)
        );

        assertEquals(ResultStatus.SECKILL_FAIL, exception.getStatus());
        verify(redisStateService, never()).reserve(any(), any(), any(), any(), any(), any(), anyLong());
    }

    @Test
    void pollResult_shouldReturnWaiting() {
        when(redisService.getValue(SeckillKey.resultKey(ITEM_ID, USER_ID), Integer.class)).thenReturn(0);

        SeckillResultVO result = seckillService.pollResult(ITEM_ID);

        assertEquals("WAITING", result.getStatus());
    }

    @Test
    void pollResult_shouldReturnSuccessWithOrderId() {
        when(redisService.getValue(SeckillKey.resultKey(ITEM_ID, USER_ID), Integer.class)).thenReturn(1);
        when(orderMapper.selectOne(any())).thenReturn(new SeckillOrder().setOrderId(9001L));

        SeckillResultVO result = seckillService.pollResult(ITEM_ID);

        assertEquals("SUCCESS", result.getStatus());
        assertEquals(9001L, result.getOrderId());
    }

    @Test
    void pollResult_shouldReturnFailed() {
        when(redisService.getValue(SeckillKey.resultKey(ITEM_ID, USER_ID), Integer.class)).thenReturn(-1);

        SeckillResultVO result = seckillService.pollResult(ITEM_ID);

        assertEquals("FAILED", result.getStatus());
    }

    private void stubReservation(Long result) {
        stubSnapshot();
        when(redisStateService.reserve(
                eq(ITEM_ID), eq(USER_ID), eq(PATH), eq(1), eq(1), anyString(), eq(3600L)
        )).thenReturn(result);
        when(messagePublisher.publishAsync(any())).thenReturn(
                CompletableFuture.completedFuture(new SeckillMessagePublisher.PublishResult(true, null))
        );
    }

    private void stubSnapshot() {
        doReturn(requestSnapshot).when(redisService).getValue(
                SeckillKey.requestSnapshotKey(ITEM_ID, USER_ID),
                SeckillRequestSnapshot.class
        );
    }
}
