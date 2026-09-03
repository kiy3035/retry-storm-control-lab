package dev.retrystorm.lab.retry;

import java.time.Duration;

import org.springframework.retry.RetryContext;
import org.springframework.retry.backoff.BackOffContext;
import org.springframework.retry.backoff.BackOffInterruptedException;
import org.springframework.retry.backoff.BackOffPolicy;
import org.springframework.retry.backoff.Sleeper;

public final class ExponentialJitterBackOffPolicy implements BackOffPolicy {

    private final long initialDelayMillis;
    private final double multiplier;
    private final long maxDelayMillis;
    private final double jitterRatio;
    private final JitterSource jitterSource;
    private final Sleeper sleeper;

    public ExponentialJitterBackOffPolicy(
            Duration initialDelay,
            double multiplier,
            Duration maxDelay,
            double jitterRatio,
            JitterSource jitterSource,
            Sleeper sleeper) {
        this.initialDelayMillis = initialDelay.toMillis();
        this.multiplier = multiplier;
        this.maxDelayMillis = maxDelay.toMillis();
        this.jitterRatio = jitterRatio;
        this.jitterSource = jitterSource;
        this.sleeper = sleeper;
    }

    @Override
    public BackOffContext start(RetryContext context) {
        return new ExponentialJitterBackOffContext();
    }

    @Override
    public void backOff(BackOffContext backOffContext) throws BackOffInterruptedException {
        if (!(backOffContext instanceof ExponentialJitterBackOffContext context)) {
            throw new IllegalArgumentException("지원하지 않는 backOffContext입니다.");
        }

        context.retryIndex++;
        double sample = jitterSource.nextDouble();
        long delayMillis = calculateDelayMillis(context.retryIndex, sample);
        try {
            sleeper.sleep(delayMillis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BackOffInterruptedException("재시도 대기가 중단되었습니다.", exception);
        }
    }

    long calculateDelayMillis(int retryIndex, double sample) {
        if (retryIndex < 1) {
            throw new IllegalArgumentException("retryIndex는 1 이상이어야 합니다.");
        }
        if (!Double.isFinite(sample) || sample < 0.0 || sample >= 1.0) {
            throw new IllegalArgumentException("jitter 표본은 0 이상 1 미만이어야 합니다.");
        }

        double exponential = initialDelayMillis * Math.pow(multiplier, retryIndex - 1);
        long baseDelay = exponential >= maxDelayMillis
                ? maxDelayMillis
                : Math.round(exponential);
        double lowerBound = baseDelay * (1.0 - jitterRatio);
        double upperBound = baseDelay * (1.0 + jitterRatio);
        long jitteredDelay = Math.round(lowerBound + (upperBound - lowerBound) * sample);
        return Math.min(maxDelayMillis, Math.max(0L, jitteredDelay));
    }

    private static final class ExponentialJitterBackOffContext implements BackOffContext {
        private int retryIndex;
    }
}
