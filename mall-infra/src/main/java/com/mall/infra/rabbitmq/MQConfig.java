package com.mall.infra.rabbitmq;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * RabbitMQ 交换机/队列/绑定关系配置。
 *
 * 命名规范：<应用>.<业务域>.<用途>
 *   交换机：mall.seckill
 *   队列：  mall.seckill.order（正常消费）
 *   死信：  mall.seckill.order.dlq（Dead Letter Queue）
 *   routing key：order.create / order.create.dlx
 *
 * ===== 秒杀消息流转 =====
 * 1. Redis 预减库存成功 → 发送消息到交换机 mall.seckill，routing key = order.create
 * 2. 消息进入队列 mall.seckill.order
 * 3. 消费者 → MySQL 扣库存 + 生成订单
 * 4. 消费成功 → ack
 * 5. 消费失败 → nack → 自动进入死信队列 mall.seckill.order.dlq
 * 6. 死信消息 → 人工排查或定时补偿（回滚 Redis 库存）
 */
@Configuration
public class MQConfig {

    // ---- 交换机 ----
    public static final String SECKILL_EXCHANGE = "mall.seckill.direct";

    // ---- 队列 ----
    public static final String SECKILL_QUEUE     = "mall.seckill.order";
    public static final String SECKILL_DLQ_QUEUE = "mall.seckill.order.dlq";

    // ---- routing key ----
    public static final String SECKILL_ROUTING_KEY     = "order.create";
    public static final String SECKILL_DLX_ROUTING_KEY = "order.create.dlx";

    /**
     * Direct 交换机，按 routing key 精确投递。
     */
    @Bean
    public DirectExchange seckillExchange() {
        return new DirectExchange(SECKILL_EXCHANGE);
    }

    /**
     * 秒杀订单队列。
     * 设置了死信交换机：消息被 reject 或 TTL 过期后自动转发到 dlq。
     */
    @Bean
    public Queue seckillQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", SECKILL_EXCHANGE);
        args.put("x-dead-letter-routing-key", SECKILL_DLX_ROUTING_KEY);
        return QueueBuilder.durable(SECKILL_QUEUE).withArguments(args).build();
    }

    /**
     * 死信队列（Dead Letter Queue）。
     * 消费失败的消息在此堆积，等待人工处理或补偿任务消费。
     */
    @Bean
    public Queue seckillDlqQueue() {
        return QueueBuilder.durable(SECKILL_DLQ_QUEUE).build();
    }

    // ---- 绑定：交换机 → 主队列 ----
    @Bean
    public Binding seckillBinding() {
        return BindingBuilder.bind(seckillQueue())
                .to(seckillExchange())
                .with(SECKILL_ROUTING_KEY);
    }

    // ---- 绑定：交换机 → 死信队列 ----
    @Bean
    public Binding seckillDlqBinding() {
        return BindingBuilder.bind(seckillDlqQueue())
                .to(seckillExchange())
                .with(SECKILL_DLX_ROUTING_KEY);
    }
}
