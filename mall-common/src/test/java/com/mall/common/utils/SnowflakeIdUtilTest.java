package com.mall.common.utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 雪花 ID 生成器。
 *
 * <p>唯一性依赖两点：整个 JVM 内只有一个生成器实例（新建实例会从自己的序列号
 * 重新开始），以及 {@code (workerId, datacenterId)} 在所有实例间互不相同。
 * 此前 workerId 由 Hutool 按 MAC + PID 推导、只有 32 个槽位，同主机多实例时
 * 撞车概率很高；现在改为启动时显式注入，缺配置直接拒绝生成。</p>
 */
class SnowflakeIdUtilTest {

    private static final long WORKER_ID = 7L;
    private static final long DATACENTER_ID = 11L;

    @BeforeEach
    void setUp() {
        SnowflakeIdUtil.resetForTesting();
        SnowflakeIdUtil.configure(WORKER_ID, DATACENTER_ID);
    }

    @AfterEach
    void tearDown() {
        SnowflakeIdUtil.resetForTesting();
    }

    /** 雪花 ID 可反解：低 12 位是序列号，往上 5 位 workerId，再往上 5 位 datacenterId。 */
    private static long workerIdOf(long id) {
        return (id >> 12) & 0x1F;
    }

    private static long datacenterIdOf(long id) {
        return (id >> 17) & 0x1F;
    }

    @Test
    void nextId_shouldEmbedConfiguredWorkerAndDatacenterId() {
        for (int i = 0; i < 1_000; i++) {
            long id = SnowflakeIdUtil.nextId();
            assertEquals(WORKER_ID, workerIdOf(id), "workerId 未按配置写入");
            assertEquals(DATACENTER_ID, datacenterIdOf(id), "datacenterId 未按配置写入");
        }
    }

    @Test
    void nextId_shouldFailWhenNotConfigured() {
        SnowflakeIdUtil.resetForTesting();

        // fail-closed：宁可报错，也不能退回「按 MAC+PID 推导」那条会静默撞车的路
        assertThrows(IllegalStateException.class, SnowflakeIdUtil::nextId);
    }

    @Test
    void configure_shouldRejectValuesOutsideFiveBitRange() {
        SnowflakeIdUtil.resetForTesting();

        assertThrows(IllegalArgumentException.class, () -> SnowflakeIdUtil.configure(-1L, 0L));
        assertThrows(IllegalArgumentException.class, () -> SnowflakeIdUtil.configure(32L, 0L));
        assertThrows(IllegalArgumentException.class, () -> SnowflakeIdUtil.configure(0L, -1L));
        assertThrows(IllegalArgumentException.class, () -> SnowflakeIdUtil.configure(0L, 32L));
    }

    @Test
    void configure_shouldRejectConflictingReconfiguration() {
        // 换一套 workerId 会新建生成器、序列号从头开始，可能重复发号
        assertThrows(IllegalStateException.class, () -> SnowflakeIdUtil.configure(8L, DATACENTER_ID));
    }

    @Test
    void configure_shouldBeIdempotentForSameValues() {
        SnowflakeIdUtil.configure(WORKER_ID, DATACENTER_ID);
        SnowflakeIdUtil.configure(WORKER_ID, DATACENTER_ID);

        assertTrue(SnowflakeIdUtil.nextId() > 0);
    }

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
