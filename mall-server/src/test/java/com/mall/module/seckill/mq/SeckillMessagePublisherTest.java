package com.mall.module.seckill.mq;

import com.mall.infra.rabbitmq.MQConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.core.MessagePostProcessor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;

@ExtendWith(MockitoExtension.class)
class SeckillMessagePublisherTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    @InjectMocks
    private SeckillMessagePublisher publisher;

    @Test
    void publishAsync_shouldCompleteOnlyAfterBrokerAck() {
        SeckillMessage message = message();
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(4);
            correlationData.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).convertAndSend(
                eq(MQConfig.SECKILL_EXCHANGE),
                eq(MQConfig.SECKILL_ROUTING_KEY),
                same(message),
                any(MessagePostProcessor.class),
                any(CorrelationData.class)
        );

        SeckillMessagePublisher.PublishResult result = publisher.publishAsync(message).join();

        assertTrue(result.confirmed());
    }

    @Test
    void publishAsync_shouldExposeBrokerNack() {
        SeckillMessage message = message();
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(4);
            correlationData.getFuture().complete(new CorrelationData.Confirm(false, "rejected"));
            return null;
        }).when(rabbitTemplate).convertAndSend(
                eq(MQConfig.SECKILL_EXCHANGE),
                eq(MQConfig.SECKILL_ROUTING_KEY),
                same(message),
                any(MessagePostProcessor.class),
                any(CorrelationData.class)
        );

        SeckillMessagePublisher.PublishResult result = publisher.publishAsync(message).join();

        assertFalse(result.confirmed());
    }

    private SeckillMessage message() {
        return new SeckillMessage()
                .setUserId(1L)
                .setSeckillItemId(2L)
                .setMessageId("message-1")
                .setQuantity(1);
    }
}
