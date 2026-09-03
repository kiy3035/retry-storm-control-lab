package dev.retrystorm.lab.message;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ProcessingSnapshot(
        UUID messageId,
        ProcessingState state,
        int attemptCount,
        List<Instant> attemptTimestamps) {

    public ProcessingSnapshot {
        attemptTimestamps = List.copyOf(attemptTimestamps);
    }
}
