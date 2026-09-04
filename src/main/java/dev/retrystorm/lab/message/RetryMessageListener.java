package dev.retrystorm.lab.message;

import dev.retrystorm.lab.dlq.DeadLetterService;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

@Component
public class RetryMessageListener {

    private final SyntheticMessageProcessor processor;
    private final MessageProcessingTracker tracker;
    private final RetryTemplate retryTemplate;
    private final DeadLetterService deadLetters;

    public RetryMessageListener(
            SyntheticMessageProcessor processor,
            MessageProcessingTracker tracker,
            RetryTemplate retryTemplate, DeadLetterService deadLetters) {
        this.processor = processor;
        this.tracker = tracker;
        this.retryTemplate = retryTemplate;
        this.deadLetters = deadLetters;
    }

    @RabbitListener(queues = "${lab.messaging.work-queue}")
    public void consume(RetryMessage message) {
        tracker.ensureRegistered(message);
        retryTemplate.execute(
                context -> {
                    processor.process(message, context.getRetryCount() + 1);
                    return null;
                },
                context -> {
                    try {
                        deadLetters.store(message, context.getRetryCount());
                    } catch (RuntimeException exception) {
                        tracker.markPersistenceFailed(message.messageId());
                        throw new AmqpRejectAndDontRequeueException("DLQ 저장 실패: 격리 큐로 이동합니다.");
                    }
                    tracker.markFailed(message.messageId());
                    return null;
                });
    }
}
