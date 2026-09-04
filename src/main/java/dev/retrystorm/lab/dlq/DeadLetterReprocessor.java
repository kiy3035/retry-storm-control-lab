package dev.retrystorm.lab.dlq;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import dev.retrystorm.lab.message.SyntheticMessageProcessor;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;

@Service
public class DeadLetterReprocessor {

    private final DeadLetterService service;
    private final SyntheticMessageProcessor processor;
    private final RetryTemplate retryTemplate;

    public DeadLetterReprocessor(DeadLetterService service,
            SyntheticMessageProcessor processor, RetryTemplate retryTemplate) {
        this.service = service;
        this.processor = processor;
        this.retryTemplate = retryTemplate;
    }

    public DeadLetterView reprocess(UUID id, long version, Integer overrideFailures) {
        var claim = service.claim(id, version, overrideFailures);
        var attempts = new AtomicInteger();
        boolean succeeded = retryTemplate.execute(context -> {
            processor.processAttempt(claim.message(), attempts.incrementAndGet());
            return true;
        }, context -> false);
        return service.complete(id, claim.version(), succeeded, attempts.get());
    }
}
