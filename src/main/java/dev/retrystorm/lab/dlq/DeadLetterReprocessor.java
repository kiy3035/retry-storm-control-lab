package dev.retrystorm.lab.dlq;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import dev.retrystorm.lab.message.SyntheticMessageProcessor;
import dev.retrystorm.lab.metrics.LabMetrics;
import dev.retrystorm.lab.metrics.LabMetrics.Outcome;
import dev.retrystorm.lab.metrics.LabMetrics.Path;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;

@Service
public class DeadLetterReprocessor {

    private final DeadLetterService service;
    private final SyntheticMessageProcessor processor;
    private final RetryTemplate retryTemplate;
    private final LabMetrics metrics;

    public DeadLetterReprocessor(DeadLetterService service,
            SyntheticMessageProcessor processor, RetryTemplate retryTemplate, LabMetrics metrics) {
        this.service = service;
        this.processor = processor;
        this.retryTemplate = retryTemplate;
        this.metrics = metrics;
    }

    public DeadLetterView reprocess(UUID id, long version, Integer overrideFailures) {
        DeadLetterService.Claim claim;
        try {
            claim = service.claim(id, version, overrideFailures);
        } catch (DeadLetterConflictException | OptimisticLockingFailureException exception) {
            metrics.conflict();
            throw exception;
        }
        long started = System.nanoTime();
        Outcome outcome = Outcome.ERROR;
        var attempts = new AtomicInteger();
        try {
            boolean succeeded = retryTemplate.execute(context -> {
                int attempt = attempts.incrementAndGet();
                metrics.attempt(Path.REPLAY, attempt);
                processor.processAttempt(claim.message(), attempt);
                return true;
            }, context -> false);
            var result = service.complete(id, claim.version(), succeeded, attempts.get());
            outcome = succeeded ? Outcome.SUCCEEDED : Outcome.FAILED;
            return result;
        } finally {
            metrics.completed(Path.REPLAY, outcome, started);
        }
    }
}
