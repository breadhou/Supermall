package com.mall.common.utils;

import cn.hutool.core.lang.Snowflake;

/**
 * 雪花 ID 生成器。
 *
 * <p><b>workerId 与 datacenterId 必须显式注入，不再自动推导。</b></p>
 *
 * <p>此前用的是 Hutool 的 {@code IdUtil.getSnowflake()} 无参形式，它会按
 * 「MAC 地址最后两个字节」推导 datacenterId、按「datacenterId + PID 的哈希」
 * 推导 workerId，两者各只有 5 位即 32 个槽位。同一台机器上所有实例 MAC 相同，
 * datacenterId 恒定，workerId 退化成 PID 哈希进 32 个桶——生日问题下 8 个实例
 * 撞车概率就有 61.4%。一旦两个实例共用同一个 (datacenterId, workerId)，在
 * 同一毫秒各自从序列号 0 开始发号，产出的 64 位 ID 会**逐位相同**。</p>
 *
 * <p>现在改由启动配置注入，缺配置时 {@link #nextId()} 直接抛错而非退回推导——
 * 推导出的值在单实例下没问题，恰恰是多实例高 QPS 时才会静默发重号。</p>
 */
public final class SnowflakeIdUtil {

    /** 位布局决定的取值范围：各 5 位。 */
    private static final long MAX_WORKER_ID = 31L;
    private static final long MAX_DATACENTER_ID = 31L;

    private static volatile Holder holder;

    private SnowflakeIdUtil() {
    }

    /**
     * 启动时注入实例身份。
     *
     * <p>以相同值重复调用是无害的；换成不同的值会被拒绝——那会新建一个生成器，
     * 序列号从头开始，可能重复发号。</p>
     */
    public static synchronized void configure(long workerId, long datacenterId) {
        checkRange(workerId, MAX_WORKER_ID, "workerId");
        checkRange(datacenterId, MAX_DATACENTER_ID, "datacenterId");

        Holder current = holder;
        if (current != null) {
            if (current.workerId() != workerId || current.datacenterId() != datacenterId) {
                throw new IllegalStateException(
                        "SnowflakeIdUtil already configured with workerId=" + current.workerId()
                                + ", datacenterId=" + current.datacenterId()
                                + "; reconfiguring would restart the sequence and risk duplicate ids");
            }
            return;
        }
        holder = new Holder(new Snowflake(workerId, datacenterId), workerId, datacenterId);
    }

    public static long nextId() {
        return instance().nextId();
    }

    public static String nextIdStr() {
        return instance().nextIdStr();
    }

    private static Snowflake instance() {
        Holder current = holder;
        if (current == null) {
            throw new IllegalStateException(
                    "SnowflakeIdUtil has not been configured. Set mall.id.worker-id and "
                            + "mall.id.datacenter-id (env MALL_WORKER_ID / MALL_DATACENTER_ID); "
                            + "every instance needs its own pair, otherwise ids collide.");
        }
        return current.snowflake();
    }

    private static void checkRange(long value, long max, String name) {
        if (value < 0 || value > max) {
            throw new IllegalArgumentException(
                    name + " must be between 0 and " + max + " (5 bits), got " + value);
        }
    }

    /** 仅供测试重置静态状态。 */
    static void resetForTesting() {
        holder = null;
    }

    private record Holder(Snowflake snowflake, long workerId, long datacenterId) {
    }
}
