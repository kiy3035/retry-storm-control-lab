package dev.retrystorm.lab.message;

import dev.retrystorm.lab.dlq.DeadLetterService;
import dev.retrystorm.lab.metrics.LabMetrics;
import dev.retrystorm.lab.metrics.LabMetrics.Outcome;
import dev.retrystorm.lab.metrics.LabMetrics.Path;
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
    private final LabMetrics metrics;

    public RetryMessageListener(
            SyntheticMessageProcessor processor,
            MessageProcessingTracker tracker,
            RetryTemplate retryTemplate, DeadLetterService deadLetters, LabMetrics metrics) {
        this.processor = processor;
        this.tracker = tracker;
        this.retryTemplate = retryTemplate;
        this.deadLetters = deadLetters;
        this.metrics = metrics;
    }

    @RabbitListener(queues = "${lab.messaging.work-queue}")
    public void consume(RetryMessage message) {
        tracker.ensureRegistered(message);
        long started = System.nanoTime();
        Outcome outcome = Outcome.ERROR;
        try {
            outcome = retryTemplate.execute(
                context -> {
                    metrics.attempt(Path.CONSUME, context.getRetryCount() + 1);
                    processor.process(message, context.getRetryCount() + 1);
                    return Outcome.SUCCEEDED;
                },
                context -> {
                    try {
                        deadLetters.store(message, context.getRetryCount());
                    } catch (RuntimeException exception) {
                        metrics.stored(LabMetrics.StoreOutcome.ERROR);
                        tracker.markPersistenceFailed(message.messageId());
                        throw new AmqpRejectAndDontRequeueException("DLQ 저장 실패: 격리 큐로 이동합니다.");
                    }
                    tracker.markFailed(message.messageId());
                    return Outcome.FAILED;
                });
        } finally {
            metrics.completed(Path.CONSUME, outcome, started);
            metrics.deliveryLatency(outcome, message.publishedAt());
        }
    }
}
