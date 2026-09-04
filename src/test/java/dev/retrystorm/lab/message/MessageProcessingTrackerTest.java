package dev.retrystorm.lab.message;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MessageProcessingTrackerTest {
    @Test
    void recordsServerPublicationAndCompletionWithoutPayload() {
        var tracker = new MessageProcessingTracker();
        var message = new RetryMessage(UUID.randomUUID(), "본문 비노출", 0, Instant.now());
        var initial = tracker.register(message);
        assertThat(initial.completedAt()).isNull();
        assertThat(initial.publishedAt()).isEqualTo(message.publishedAt());
        tracker.recordAttempt(message.messageId());
        tracker.markSucceeded(message.messageId());
        var completed = tracker.find(message.messageId()).orElseThrow();
        assertThat(completed.completedAt()).isAfterOrEqualTo(completed.attemptTimestamps().getFirst());
        assertThat(completed.toString()).doesNotContain(message.payload());
    }

    @Test
    void redeliveryClearsPreviousCompletionUntilTerminatedAgain() {
        var tracker = new MessageProcessingTracker();
        var message = new RetryMessage(UUID.randomUUID(), "재전달", 3, Instant.now());
        tracker.ensureRegistered(message);
        tracker.recordAttempt(message.messageId());
        tracker.markFailed(message.messageId());
        assertThat(tracker.find(message.messageId()).orElseThrow().completedAt()).isNotNull();
        tracker.recordAttempt(message.messageId());
        assertThat(tracker.find(message.messageId()).orElseThrow().completedAt()).isNull();
        tracker.markPersistenceFailed(message.messageId());
        assertThat(tracker.find(message.messageId()).orElseThrow().completedAt()).isNotNull();
    }
}
