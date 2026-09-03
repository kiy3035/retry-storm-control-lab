package dev.retrystorm.lab.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("lab.retry")
public record RetryProperties(
        int maxAttempts,
        RetryMode mode,
        Duration fixedDelay,
        Duration initialDelay,
        double multiplier,
        Duration maxDelay,
        double jitterRatio) {

    public RetryProperties {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts는 1 이상이어야 합니다.");
        }
        if (mode == null) {
            throw new IllegalArgumentException("mode는 필수입니다.");
        }
        if (fixedDelay == null || fixedDelay.isNegative()) {
            throw new IllegalArgumentException("fixedDelay는 0 이상이어야 합니다.");
        }
        if (initialDelay == null || initialDelay.isZero() || initialDelay.isNegative()) {
            throw new IllegalArgumentException("initialDelay는 0보다 커야 합니다.");
        }
        if (!Double.isFinite(multiplier) || multiplier < 1.0) {
            throw new IllegalArgumentException("multiplier는 1 이상의 유한한 값이어야 합니다.");
        }
        if (maxDelay == null || maxDelay.compareTo(initialDelay) < 0) {
            throw new IllegalArgumentException("maxDelay는 initialDelay 이상이어야 합니다.");
        }
        if (!Double.isFinite(jitterRatio) || jitterRatio < 0.0 || jitterRatio >= 1.0) {
            throw new IllegalArgumentException("jitterRatio는 0 이상 1 미만이어야 합니다.");
        }
    }
}
