package com.mall.module.seckill.mq;

import com.mall.infra.rabbitmq.MQConfig;
import com.mall.module.seckill.monitor.SeckillMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.concurrent.CompletableFuture;

/**
 * Asynchronous RabbitMQ publisher-confirm adapter.  The API service may await
 * the returned future with a bounded timeout, while the Rabbit client handles
 * the confirm callback on its own connection thread.
 */
@Slf4j
@Component
public class SeckillMessagePublisher {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired(required = false)
    private SeckillMetrics metrics;

    @PostConstruct
    void configureReturns() {
        rabbitTemplate.setMandatory(true);
        rabbitTemplate.setReturnsCallback(returned ->
                log.error("Seckill message was returned by RabbitMQ: exchange={}, routingKey={}, replyCode={}, replyText={}",
                        returned.getExchange(), returned.getRoutingKey(), returned.getReplyCode(), returned.getReplyText()));
    }

    public CompletableFuture<PublishResult> publishAsync(SeckillMessage message) {
        CorrelationData correlationData = new CorrelationData(message.getMessageId());
        long started = System.nanoTime();
        try {
            rabbitTemplate.convertAndSend(
                    MQConfig.SECKILL_EXCHANGE,
                    MQConfig.SECKILL_ROUTING_KEY,
                    message,
                    outbound -> {
                        outbound.getMessageProperties().setMessageId(message.getMessageId());
                        return outbound;
                    },
                    correlationData
            );
        } catch (RuntimeException exception) {
            record(false, started);
            return CompletableFuture.completedFuture(new PublishResult(false, exception.getMessage()));
        }

        return correlationData.getFuture().handle((confirm, exception) -> {
            boolean confirmed = exception == null
                    && confirm != null
                    && confirm.isAck()
                    && correlationData.getReturned() == null;
            String reason = exception == null
                    ? (confirm == null ? "missing-confirm" : confirm.getReason())
                    : exception.getMessage();
            record(confirmed, started);
            return new PublishResult(confirmed, reason);
        });
    }

    private void record(boolean confirmed, long started) {
        if (metrics != null) {
            metrics.recordPublish(confirmed, System.nanoTime() - started);
        }
    }

    public record PublishResult(boolean confirmed, String reason) {
    }
}
