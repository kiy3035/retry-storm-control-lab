package dev.retrystorm.lab.dlq;

import java.time.Instant;
import java.util.UUID;

import dev.retrystorm.lab.message.RetryMessage;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "dead_letters", schema = "retry_lab")
public class DeadLetter {

    @Id
    private UUID messageId;
    @Column(nullable = false, columnDefinition = "text")
    private String payload;
    private int failuresBeforeSuccess;
    private Instant publishedAt;
    private Instant failedAt;
    private String failureCode;
    private int originalAttempts;
    @Enumerated(EnumType.STRING)
    private DeadLetterState state;
    private int replayAttempts;
    private int reprocessCount;
    private Instant updatedAt;
    @Version
    private Long version;

    protected DeadLetter() {
    }

    public void claim(long expectedVersion) {
        if (version != expectedVersion
                || (state != DeadLetterState.PENDING && state != DeadLetterState.FAILED)) {
            throw new DeadLetterConflictException();
        }
        state = DeadLetterState.PROCESSING;
        replayAttempts = 0;
        reprocessCount++;
        updatedAt = Instant.now();
    }

    public void complete(long expectedVersion, boolean succeeded, int attempts) {
        if (version != expectedVersion || state != DeadLetterState.PROCESSING) {
            throw new DeadLetterConflictException();
        }
        state = succeeded ? DeadLetterState.SUCCEEDED : DeadLetterState.FAILED;
        replayAttempts = attempts;
        updatedAt = Instant.now();
    }

    public RetryMessage message(Integer overrideFailures) {
        return new RetryMessage(messageId, payload,
                overrideFailures == null ? failuresBeforeSuccess : overrideFailures, publishedAt);
    }

    public DeadLetterView view() {
        return new DeadLetterView(messageId, failureCode, originalAttempts, state,
                replayAttempts, reprocessCount, failedAt, updatedAt, version);
    }
}
