package com.mall.infra.redis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private RedisService redisService;

    @BeforeEach
    void setUp() {
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        redisService = new RedisService(stringRedisTemplate);
    }

    @Test
    void getShouldReadStringScalar() {
        when(valueOperations.get("mall:seckill:path:1:2")).thenReturn("test-path");

        String value = redisService.get(SeckillKey.path, "1:2", String.class);

        assertEquals("test-path", value);
    }

    @Test
    void getShouldReadIntegerScalar() {
        when(valueOperations.get(SeckillKey.stockKey(1L))).thenReturn("2");

        Integer value = redisService.getValue(SeckillKey.stockKey(1L), Integer.class);

        assertEquals(2, value);
    }

    @Test
    void seckillKeysKeepTheItemHashTagForClusterScripts() {
        assertEquals("mall:seckill:{1}:stock", SeckillKey.stockKey(1L));
        assertEquals("mall:seckill:{1}:path:2", SeckillKey.pathKey(1L, 2L));
        assertEquals("mall:seckill:{1}:result:2", SeckillKey.resultKey(1L, 2L));
        assertEquals("mall:seckill:{1}:pending:message-1", SeckillKey.pendingKey(1L, "message-1"));
    }

    @Test
    void getShouldKeepJsonObjectDeserialization() {
        when(valueOperations.get("mall:test:payload:1"))
                .thenReturn("{\"name\":\"demo\"}");

        RedisPayload value = redisService.get(new TestPrefix(), "1", RedisPayload.class);

        assertEquals("demo", value.getName());
    }

    private static final class TestPrefix implements KeyPrefix {
        @Override
        public int expireSeconds() {
            return 0;
        }

        @Override
        public String getPrefix() {
            return "mall:test:payload:";
        }
    }

    public static class RedisPayload {
        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }
}
