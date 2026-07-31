package com.mall.module.seckill.monitor;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Small, low-overhead metrics facade for the seckill hot and async paths. */
@Component
public class SeckillMetrics {

    private final MeterRegistry registry;
    private final AtomicLong pendingMessages = new AtomicLong();

    public SeckillMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("mall.seckill.pending.messages", pendingMessages, AtomicLong::get)
                .description("Pending seckill messages observed by this instance")
                .register(registry);
    }

    public void recordEntry(String outcome) {
        Counter.builder("mall.seckill.entry.requests")
                .tag("outcome", outcome)
                .register(registry)
                .increment();
    }

    public void recordLua(long elapsedNanos) {
        Timer.builder("mall.seckill.lua")
                .register(registry)
                .record(elapsedNanos, TimeUnit.NANOSECONDS);
    }

    public void recordPublish(boolean confirmed, long elapsedNanos) {
        Counter.builder("mall.seckill.publisher.confirmations")
                .tag("result", confirmed ? "ack" : "failed")
                .register(registry)
                .increment();
        Timer.builder("mall.seckill.publisher.confirm")
                .register(registry)
                .record(elapsedNanos, TimeUnit.NANOSECONDS);
    }

    public void recordConsumer(boolean success, long elapsedNanos) {
        Counter.builder("mall.seckill.consumer.messages")
                .tag("result", success ? "success" : "failed")
                .register(registry)
                .increment();
        Timer.builder("mall.seckill.consumer.transaction")
                .register(registry)
                .record(elapsedNanos, TimeUnit.NANOSECONDS);
    }

    public void recordCompensation(String outcome) {
        Counter.builder("mall.seckill.compensation")
                .tag("outcome", outcome)
                .register(registry)
                .increment();
    }

    public void setPendingMessages(long count) {
        pendingMessages.set(Math.max(0, count));
    }
}
