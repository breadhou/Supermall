package com.mall.config;

import com.mall.common.utils.SnowflakeIdUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * 把实例身份注入 {@link SnowflakeIdUtil}。
 *
 * <p>两个值都**没有默认值**：缺失时应用启动即失败。这是有意的——Hutool 的自动
 * 推导在单实例下没问题，只有多实例高 QPS 时才会静默发出重复 ID，那种故障在
 * 压测数据里表现为零星的主键冲突，极难回溯。宁可起不来。</p>
 *
 * <p>每个实例必须拿到**互不相同**的一对值：同主机多实例时 datacenterId 相同、
 * workerId 不同即可（合法范围各 0~31）。</p>
 */
@Slf4j
@Configuration
public class SnowflakeIdConfig {

    public SnowflakeIdConfig(@Value("${mall.id.worker-id}") long workerId,
                             @Value("${mall.id.datacenter-id}") long datacenterId) {
        SnowflakeIdUtil.configure(workerId, datacenterId);
        log.info("Snowflake identity configured: workerId={}, datacenterId={}", workerId, datacenterId);
    }
}
