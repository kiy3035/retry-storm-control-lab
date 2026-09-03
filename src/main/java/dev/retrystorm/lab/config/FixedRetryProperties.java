package dev.retrystorm.lab.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("lab.retry")
public record FixedRetryProperties(
        int maxAttempts,
        Duration fixedDelay) {

    public FixedRetryProperties {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts는 1 이상이어야 합니다.");
        }
        if (fixedDelay == null || fixedDelay.isNegative()) {
            throw new IllegalArgumentException("fixedDelay는 0 이상이어야 합니다.");
        }
    }
}
