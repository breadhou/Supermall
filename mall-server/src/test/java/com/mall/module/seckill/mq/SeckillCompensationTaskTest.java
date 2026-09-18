package com.mall.module.seckill.mq;

import com.mall.module.seckill.entity.po.SeckillOrder;
import com.mall.module.seckill.mapper.SeckillOrderMapper;
import com.mall.module.seckill.monitor.SeckillMetrics;
import com.mall.module.seckill.redis.SeckillRedisStateService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the pending-reservation scanner.
 *
 * <p>The ordering inside {@code repairOne} is the invariant that matters most:
 * the durable {@code seckill_order} row is consulted <em>before</em> any
 * rollback decision.  A consumer may stall past {@code processing-timeout-ms}
 * and then still commit its transaction, so rolling back on timeout alone
 * would restore stock for an order that actually exists — oversell.</p>
 */
@ExtendWith(MockitoExtension.class)
class SeckillCompensationTaskTest {

    private static final Long ITEM_ID = 1001L;
    private static final Long USER_ID = 2002L;
    private static final Long ORDER_ID = 9001L;
    private static final String MESSAGE_ID = "msg-1";

    @Mock
    private SeckillRedisStateService redisStateService;
    @Mock
    private SeckillOrderMapper seckillOrderMapper;
    @Mock
    private SeckillMetrics metrics;

    @InjectMocks
    private SeckillCompensationTask task;

    private void givenPendingEntry(String state, Long processingAt) {
        when(redisStateService.registeredItems()).thenReturn(Set.of(String.valueOf(ITEM_ID)));
        when(redisStateService.pendingCount(ITEM_ID)).thenReturn(1L);
        when(redisStateService.pendingMessageIds(eq(ITEM_ID), anyLong())).thenReturn(Set.of(MESSAGE_ID));
        when(redisStateService.getPending(ITEM_ID, MESSAGE_ID)).thenReturn(
                new SeckillRedisStateService.PendingEntry(
                        state, USER_ID, 1, System.currentTimeMillis(), MESSAGE_ID, processingAt));
    }

    private void givenCommittedOrder() {
        when(seckillOrderMapper.selectOne(any())).thenReturn(new SeckillOrder().setOrderId(ORDER_ID));
    }

    @Test
    void repairPendingMessages_shouldFinalizeInsteadOfRollback_whenOrderCommittedDespiteProcessingTimeout() {
        // The reservation looks rollback-eligible: the consumer claimed it and
        // then stalled well past the processing timeout.  The committed order
        // must still win, otherwise restoring stock would oversell.
        givenPendingEntry("PROCESSING", System.currentTimeMillis() - 600_000L);
        givenCommittedOrder();
        when(redisStateService.finalizeSuccess(any(), anyLong())).thenReturn(1L);

        task.repairPendingMessages();

        verify(redisStateService).finalizeSuccess(any(), anyLong());
        verify(redisStateService, never()).rollback(any(), anyBoolean(), anyLong());
    }

    @Test
    void repairPendingMessages_shouldRollbackWithConfiguredResultTtl_whenNoOrderWasCommitted() {
        ReflectionTestUtils.setField(task, "resultTtlSeconds", 7200L);
        givenPendingEntry("PENDING", null);
        when(seckillOrderMapper.selectOne(any())).thenReturn(null);
        when(redisStateService.rollback(any(), anyBoolean(), anyLong())).thenReturn(1L);

        task.repairPendingMessages();

        verify(redisStateService).rollback(any(), eq(true), eq(7200L));
        verify(redisStateService, never()).finalizeSuccess(any(), anyLong());
    }

    @Test
    void repairPendingMessages_shouldLeaveReservationAlone_whenConsumerIsStillWithinProcessingTimeout() {
        givenPendingEntry("PROCESSING", System.currentTimeMillis());
        when(seckillOrderMapper.selectOne(any())).thenReturn(null);

        task.repairPendingMessages();

        verify(redisStateService, never()).rollback(any(), anyBoolean(), anyLong());
        verify(redisStateService, never()).finalizeSuccess(any(), anyLong());
    }

    @Test
    void repairPendingMessages_shouldFinalizeWithConfiguredResultTtl() {
        ReflectionTestUtils.setField(task, "resultTtlSeconds", 7200L);
        givenPendingEntry("PENDING", null);
        givenCommittedOrder();
        when(redisStateService.finalizeSuccess(any(), anyLong())).thenReturn(1L);

        task.repairPendingMessages();

        // A hardcoded 3600 here would silently diverge from
        // mall.seckill.result-ttl-seconds, which the consumer honours.
        verify(redisStateService).finalizeSuccess(any(), eq(7200L));
    }

    @Test
    void repairPendingMessages_shouldRecordAnomaly_whenRollbackReportsUnavailableStockKey() {
        givenPendingEntry("PENDING", null);
        when(seckillOrderMapper.selectOne(any())).thenReturn(null);
        when(redisStateService.rollback(any(), anyBoolean(), anyLong())).thenReturn(-1L);

        task.repairPendingMessages();

        // -1 means the stock key is gone, so the reservation could not be
        // returned.  Silence here hides a real oversell risk from monitoring.
        verify(metrics).recordCompensation("rollback_stock_missing");
    }
}
