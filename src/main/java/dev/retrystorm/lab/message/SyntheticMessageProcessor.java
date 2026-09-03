package dev.retrystorm.lab.message;

import org.springframework.stereotype.Component;

@Component
public class SyntheticMessageProcessor {

    private final MessageProcessingTracker tracker;

    public SyntheticMessageProcessor(MessageProcessingTracker tracker) {
        this.tracker = tracker;
    }

    public void process(RetryMessage message) {
        int attempt = tracker.recordAttempt(message.messageId());
        if (attempt <= message.failuresBeforeSuccess()) {
            throw new RetryableMessageProcessingException(
                    "합성 실패가 발생했습니다. messageId=%s, attempt=%d"
                            .formatted(message.messageId(), attempt));
        }
        tracker.markSucceeded(message.messageId());
    }
}
