package dev.retrystorm.lab.message;

import java.time.Instant;
import java.util.UUID;

import dev.retrystorm.lab.config.MessagingProperties;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class RetryMessagePublisher {

    private final RabbitTemplate rabbitTemplate;
    private final MessagingProperties properties;
    private final MessageProcessingTracker tracker;

    public RetryMessagePublisher(
            RabbitTemplate rabbitTemplate,
            MessagingProperties properties,
            MessageProcessingTracker tracker) {
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
        this.tracker = tracker;
    }

    public ProcessingSnapshot publish(String payload, int failuresBeforeSuccess) {
        var message = new RetryMessage(
                UUID.randomUUID(),
                payload,
                failuresBeforeSuccess,
                Instant.now());
        var snapshot = tracker.register(message);
        try {
            rabbitTemplate.convertAndSend(
                    properties.exchange(),
                    properties.routingKey(),
                    message);
            return snapshot;
        } catch (AmqpException exception) {
            tracker.markFailed(message.messageId());
            throw exception;
        }
    }
}
