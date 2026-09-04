package dev.retrystorm.lab.dlq;

import java.time.Instant;
import java.util.UUID;

public record DeadLetterView(
        UUID messageId, String failureCode, int originalAttempts,
        DeadLetterState state, int replayAttempts, int reprocessCount,
        Instant failedAt, Instant updatedAt, long version) {
}
