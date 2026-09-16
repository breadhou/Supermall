package com.mall.common.utils;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the snowflake id generator.
 *
 * <p>The wrapper delegates to Hutool's {@code IdUtil.getSnowflake()}.  The
 * generator is only safe if that call keeps returning the same instance: a
 * freshly constructed generator starts from its own sequence, so repeated
 * instantiation would hand out colliding ids.</p>
 */
class SnowflakeIdUtilTest {

    @Test
    void nextId_shouldReturnUniqueIdsInSequence() {
        int count = 50_000;
        Set<Long> ids = new HashSet<>(count * 2);

        for (int i = 0; i < count; i++) {
            assertTrue(ids.add(SnowflakeIdUtil.nextId()), "duplicate id at index " + i);
        }

        assertEquals(count, ids.size());
    }

    @Test
    void nextId_shouldReturnUniqueIdsUnderConcurrentAccess() throws InterruptedException {
        int threads = 8;
        int perThread = 5_000;
        Set<Long> ids = ConcurrentHashMap.newKeySet();
        AtomicInteger duplicates = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            Thread worker = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        if (!ids.add(SnowflakeIdUtil.nextId())) {
                            duplicates.incrementAndGet();
                        }
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }, "snowflake-" + t);
            worker.start();
        }

        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "id generation did not finish in time");

        assertEquals(0, duplicates.get(), "duplicate ids under concurrent access");
        assertEquals(threads * perThread, ids.size());
    }

    @Test
    void nextId_shouldIncreaseStrictlyWithinOneThread() {
        long previous = SnowflakeIdUtil.nextId();

        for (int i = 0; i < 10_000; i++) {
            long current = SnowflakeIdUtil.nextId();
            assertTrue(current > previous, "id did not increase: " + previous + " -> " + current);
            previous = current;
        }
    }

    @Test
    void nextId_shouldReturnPositiveValues() {
        for (int i = 0; i < 1_000; i++) {
            assertTrue(SnowflakeIdUtil.nextId() > 0, "snowflake id must stay positive");
        }
    }

    @Test
    void nextIdStr_shouldReturnUniqueNumericValues() {
        Set<String> ids = new HashSet<>();

        for (int i = 0; i < 5_000; i++) {
            String id = SnowflakeIdUtil.nextIdStr();
            assertTrue(id.matches("\\d+"), "not a numeric id: " + id);
            assertTrue(ids.add(id), "duplicate string id: " + id);
        }
    }
}
