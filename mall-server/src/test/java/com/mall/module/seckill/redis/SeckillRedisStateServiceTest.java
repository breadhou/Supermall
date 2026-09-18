package com.mall.module.seckill.redis;

import com.mall.module.seckill.mq.SeckillMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the Lua-backed reservation state machine.
 *
 * <p>These lock the arguments handed to the scripts.  A hardcoded value inside
 * the service would silently diverge from the configuration the API and the
 * consumer read, which is exactly how the result TTL drifted before.</p>
 */
@ExtendWith(MockitoExtension.class)
class SeckillRedisStateServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @InjectMocks
    private SeckillRedisStateService service;

    /** Captures the ARGV array of every script invocation. */
    private final List<Object[]> invocations = new ArrayList<>();

    private SeckillMessage message() {
        return new SeckillMessage()
                .setUserId(2002L)
                .setSeckillItemId(1001L)
                .setMessageId("msg-1")
                .setQuantity(1);
    }

    /** Records the raw arguments of a script call and reports a benign result. */
    private Answer<Long> captureCall() {
        return invocation -> {
            invocations.add(invocation.getArguments());
            return 1L;
        };
    }

    /**
     * {@code execute(script, keys, a, b, c, d, e)} packs the trailing arguments
     * into an array, so the raw invocation may present them either packed or
     * already flattened.  Normalise both shapes.
     */
    private Object[] argvOf(Object[] raw) {
        if (raw.length == 3 && raw[2] instanceof Object[]) {
            return (Object[]) raw[2];
        }
        return Arrays.copyOfRange(raw, 2, raw.length);
    }

    @Test
    void rollback_shouldForwardResultTtlToLua() {
        when(redisTemplate.execute(any(), anyList(), any(), any(), any(), any(), any()))
                .thenAnswer(captureCall());

        service.rollback(message(), true, 7200L);

        Object[] argv = argvOf(invocations.get(0));
        // ARGV = messageId, userId, quantity, allowProcessing, resultTtlSeconds
        assertEquals("7200", argv[4]);
    }

    @Test
    void finalizeSuccess_shouldForwardResultTtlToLua() {
        when(redisTemplate.execute(any(), anyList(), any(), any()))
                .thenAnswer(captureCall());

        service.finalizeSuccess(message(), 7200L);

        Object[] argv = argvOf(invocations.get(0));
        // ARGV = messageId, resultTtlSeconds
        assertEquals("7200", argv[1]);
    }
}
