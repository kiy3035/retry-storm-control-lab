package dev.retrystorm.lab.message;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.stereotype.Component;

@Component
public class MessageProcessingTracker {

    private final ConcurrentMap<UUID, ProcessingSnapshot> snapshots = new ConcurrentHashMap<>();

    public void ensureRegistered(RetryMessage message) {
        snapshots.putIfAbsent(message.messageId(), new ProcessingSnapshot(
                message.messageId(), ProcessingState.PENDING, 0, List.of()));
    }

    public ProcessingSnapshot register(RetryMessage message) {
        var initial = new ProcessingSnapshot(
                message.messageId(),
                ProcessingState.PENDING,
                0,
                List.of());
        var existing = snapshots.putIfAbsent(message.messageId(), initial);
        if (existing != null) {
            throw new IllegalArgumentException("이미 등록된 messageId입니다.");
        }
        return initial;
    }

    public int recordAttempt(UUID messageId) {
        var updated = snapshots.compute(messageId, (ignored, current) -> {
            if (current == null) {
                throw new IllegalStateException("등록되지 않은 메시지입니다.");
            }
            var timestamps = new ArrayList<>(current.attemptTimestamps());
            timestamps.add(Instant.now());
            return new ProcessingSnapshot(
                    messageId,
                    ProcessingState.PROCESSING,
                    current.attemptCount() + 1,
                    timestamps);
        });
        return updated.attemptCount();
    }

    public void markSucceeded(UUID messageId) {
        updateState(messageId, ProcessingState.SUCCEEDED);
    }

    public void markFailed(UUID messageId) {
        updateState(messageId, ProcessingState.FAILED);
    }

    public void markPersistenceFailed(UUID messageId) {
        updateState(messageId, ProcessingState.PERSISTENCE_FAILED);
    }

    public Optional<ProcessingSnapshot> find(UUID messageId) {
        return Optional.ofNullable(snapshots.get(messageId));
    }

    private void updateState(UUID messageId, ProcessingState state) {
        snapshots.computeIfPresent(messageId, (ignored, current) -> new ProcessingSnapshot(
                messageId,
                state,
                current.attemptCount(),
                current.attemptTimestamps()));
    }
}
