package dev.retrystorm.lab.message;

import java.time.Instant;
import java.util.UUID;

import dev.retrystorm.lab.config.MessagingProperties;
import dev.retrystorm.lab.metrics.LabMetrics;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class RetryMessagePublisher {

    private final RabbitTemplate rabbitTemplate;
    private final MessagingProperties properties;
    private final MessageProcessingTracker tracker;
    private final LabMetrics metrics;

    public RetryMessagePublisher(
            RabbitTemplate rabbitTemplate,
            MessagingProperties properties,
            MessageProcessingTracker tracker, LabMetrics metrics) {
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
        this.tracker = tracker;
        this.metrics = metrics;
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
            metrics.published(true);
            return snapshot;
        } catch (AmqpException exception) {
            metrics.published(false);
            tracker.markFailed(message.messageId());
            throw exception;
        }
    }
}
