package dev.retrystorm.lab.message;

import java.time.Instant;
import java.util.UUID;

public record RetryMessage(
        UUID messageId,
        String payload,
        int failuresBeforeSuccess,
        Instant publishedAt) {

    public RetryMessage {
        if (messageId == null) {
            throw new IllegalArgumentException("messageId는 필수입니다.");
        }
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("payload는 비어 있을 수 없습니다.");
        }
        if (failuresBeforeSuccess < 0) {
            throw new IllegalArgumentException("failuresBeforeSuccess는 0 이상이어야 합니다.");
        }
        if (publishedAt == null) {
            throw new IllegalArgumentException("publishedAt은 필수입니다.");
        }
    }
}
