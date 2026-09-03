package dev.retrystorm.lab.message;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

@Component
public class RetryMessageListener {

    private final SyntheticMessageProcessor processor;
    private final MessageProcessingTracker tracker;
    private final RetryTemplate fixedRetryTemplate;

    public RetryMessageListener(
            SyntheticMessageProcessor processor,
            MessageProcessingTracker tracker,
            RetryTemplate fixedRetryTemplate) {
        this.processor = processor;
        this.tracker = tracker;
        this.fixedRetryTemplate = fixedRetryTemplate;
    }

    @RabbitListener(queues = "${lab.messaging.work-queue}")
    public void consume(RetryMessage message) {
        fixedRetryTemplate.execute(
                context -> {
                    processor.process(message);
                    return null;
                },
                context -> {
                    tracker.markFailed(message.messageId());
                    return null;
                });
    }
}
